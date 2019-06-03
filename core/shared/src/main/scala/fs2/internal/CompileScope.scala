package fs2.internal

import scala.annotation.tailrec

import cats.{Traverse, TraverseFilter}
import cats.data.Chain
import cats.effect.{Concurrent, ExitCase, Sync}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import fs2.{CompositeFailure, Pure, Scope}
import fs2.internal.CompileScope.InterruptContext

/**
  * Implementation of [[Scope]] for the internal stream interpreter.
  *
  * Represents a period of stream execution in which resources are acquired and released.
  * A scope has a state, consisting of resources (with associated finalizers) acquired in this scope
  * and child scopes spawned from this scope.
  *
  * === Scope lifetime ===
  *
  * When stream interpretation starts, one `root` scope is created. Scopes are then created and closed based on the
  * stream structure. Every time a `Pull` is converted to a `Stream`, a scope is created.
  *
  * For example, `s.chunks` is defined with `s.repeatPull` which in turn is defined with `Pull.loop(...).stream`.
  * In this case, a single scope is created as a result of the call to `.stream`.
  *
  * Scopes may also be opened and closed manually with `Stream#scope`. For the stream `s.scope`, a scope
  * is opened before evaluation of `s` and closed once `s` finishes evaluation.
  *
  * === Scope organization ===
  *
  * Scopes are organized in tree structure, with each scope having at max one parent (a root scope has no parent)
  * with 0 or more child scopes.
  *
  * Every time a new scope is created, it inherits parent from the current scope and adds itself as a child
  * of that parent.
  *
  * During the interpretation of nondeterministic streams (i.e. merge), there may be multiple scopes attached
  * to a single parent and these scopes may be created and closed in a nondeterministic order.
  *
  * A child scope never outlives its parent scope. I.e., when a parent scope is closed for whatever reason,
  * the child scopes are closed too.
  *
  * === Resources ===
  *
  * The primary role of a scope is tracking resource allocation and release. The stream interpreter guarantees that
  * resources allocated in a scope are always released when the scope closes.
  *
  * === Resource allocation ===
  *
  * Resources are allocated when the interpreter interprets the `Acquire` element, which is typically constructed
  * via `Stream.bracket` or `Pull.acquire`. See [[Resource]] docs for more information.
  *
  * @param id              Unique identification of the scope
  * @param parent          If empty indicates root scope. If non-empty, indicates parent of this scope.
  * @param interruptible   If defined, allows this scope to interrupt any of its operation. Interruption
  *                       is performed using the supplied context.
  *                       Normally the interruption awaits next step in Algebra to be evaluated, with exception
  *                       of Eval, that when interruption is enabled on scope will be wrapped in race,
  *                       that eventually allows interruption while eval is evaluating.
  *
  */
private[fs2] final class CompileScope[F[_]] private (
    val id: Token,
    private val parent: Option[CompileScope[F]],
    val interruptible: Option[InterruptContext[F]]
)(implicit val F: Sync[F])
    extends Scope[F] { self =>

  private val state: Ref[F, CompileScope.State[F]] =
    Ref.unsafe(CompileScope.State.initial)

  /**
    * Registers supplied resource in this scope.
    * This is always invoked before state can be marked as closed.
    */
  private def register(resource: Resource[F]): F[Unit] =
    state.update { s =>
      s.copy(resources = resource +: s.resources)
    }

  /**
    * Opens a child scope.
    *
    * If this scope is currently closed, then the child scope is opened on the first
    * open ancestor of this scope.
    *
    * Returns scope that has to be used in next compilation step and the next stream
    * to be evaluated.
    */
  def open(
      interruptible: Option[Concurrent[F]]
  ): F[Either[Throwable, CompileScope[F]]] = {

    /*
     * Creates a context for a new scope.
     *
     * We need to differentiate between three states:
     *  The new scope is not interruptible -
     *     It should respect the interrupt of the current scope. But it should not
     *     close the listening on parent scope close when the new scope will close.
     *
     *  The new scope is interruptible but this scope is not interruptible -
     *     This is a new interrupt root that can be only interrupted from within the new scope or its children scopes.
     *
     *  The new scope is interruptible as well as this scope is interruptible -
     *     This is a new interrupt root that can be interrupted from within the new scope, its children scopes
     *     or as a result of interrupting this scope. But it should not propagate its own interruption to this scope.
     *
     */
    val createCompileScope: F[CompileScope[F]] = {
      val newScopeId = new Token
      self.interruptible match {
        case None =>
          val iCtx = InterruptContext.unsafeFromInterruptible(interruptible, newScopeId)
          F.pure(new CompileScope[F](newScopeId, Some(self), iCtx))

        case Some(parentICtx) =>
          val fiCtx = parentICtx.childContext(interruptible, newScopeId)
          F.map(fiCtx)(iCtx => new CompileScope[F](newScopeId, Some(self), Some(iCtx)))
      }
    }

    F.flatMap(createCompileScope) { scope =>
      F.flatMap(state.modify { s =>
        if (!s.open) (s, None)
        else
          (s.copy(children = scope +: s.children), Some(scope))
      }) {
        case Some(s) => F.pure(Right(s))
        case None    =>
          // This scope is already closed so try to promote the open to an ancestor; this can fail
          // if the root scope has already been closed, in which case, we can safely throw
          self.parent match {
            case Some(parent) =>
              F.flatMap(self.interruptible.map(_.cancelParent).getOrElse(F.unit)) { _ =>
                parent.open(interruptible)
              }

            case None =>
              F.pure(Left(new IllegalStateException("cannot re-open root scope")))
          }
      }
    }
  }

  /**
    * fs2 Stream is interpreted synchronously, as such the resource acquisition is fully synchronous.
    * No next step (even when stream was interrupted) is run before the resource
    * is fully acquired.
    *
    * If, during resource acquisition the stream is interrupted, this will still await for the resource to be fully
    * acquired, and then such stream will continue, likely with resource cleanup during the interpretation of the stream.
    *
    * There is only one situation where resource cleanup may be somewhat concurrent and that is when resources are
    * leased in `parJoin`. But even then the order of the lease of the resources respects acquisition of the resources that leased them.
    */
  def acquireResource[R](
      fr: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]): F[Either[Throwable, (R, Resource[F])]] = {
    val resource = Resource.create
    F.flatMap(F.attempt(fr)) {
      case Right(r) =>
        val finalizer = (ec: ExitCase[Throwable]) => F.suspend(release(r, ec))
        F.flatMap(resource.acquired(finalizer)) { result =>
          if (result.exists(identity)) F.map(register(resource))(_ => Right((r, resource)))
          else F.pure(Left(result.swap.getOrElse(AcquireAfterScopeClosed)))
        }
      case Left(err) => F.pure(Left(err))
    }
  }

  /**
    * Unregisters the child scope identified by the supplied id.
    *
    * As a result of unregistering a child scope, its resources are no longer
    * reachable from its parent.
    */
  private def releaseChildScope(id: Token): F[Unit] =
    state.update { _.unregisterChild(id) }

  /** Returns all direct resources of this scope (does not return resources in ancestor scopes or child scopes). **/
  private def resources: F[Chain[Resource[F]]] =
    F.map(state.get) { _.resources }

  /**
    * Traverses supplied `Chain` with `f` that may produce a failure, and collects these failures.
    * Returns failure with collected failures, or `Unit` on successful traversal.
    */
  private def traverseError[A](ca: Chain[A],
                               f: A => F[Either[Throwable, Unit]]): F[Either[Throwable, Unit]] =
    F.map(Traverse[Chain].traverse(ca)(f)) { results =>
      CompositeFailure
        .fromList(results.collect { case Left(err) => err }.toList)
        .toLeft(())
    }

  /**
    * Closes this scope.
    *
    * All resources of this scope are released when this is evaluated.
    *
    * Also this will close the child scopes (if any) and release their resources.
    *
    * If this scope has a parent scope, this scope will be unregistered from its parent.
    *
    * Note that if there were leased or not yet acquired resources, these resource will not yet be
    * finalized after this scope is closed, but they will get finalized shortly after. See [[Resource]] for
    * more details.
    */
  def close(ec: ExitCase[Throwable]): F[Either[Throwable, Unit]] =
    F.flatMap(state.modify(s => s.close -> s)) { previous =>
      F.flatMap(traverseError[CompileScope[F]](previous.children, _.close(ec))) { resultChildren =>
        F.flatMap(traverseError[Resource[F]](previous.resources, _.release(ec))) {
          resultResources =>
            F.flatMap(self.interruptible.map(_.cancelParent).getOrElse(F.unit)) { _ =>
              F.map(self.parent.fold(F.unit)(_.releaseChildScope(self.id))) { _ =>
                val results = resultChildren.fold(List(_), _ => Nil) ++ resultResources.fold(
                  List(_),
                  _ => Nil)
                CompositeFailure.fromList(results.toList).toLeft(())
              }
            }
        }
      }
    }

  /** Returns closest open parent scope or root. */
  def openAncestor: F[CompileScope[F]] =
    self.parent.fold(F.pure(self)) { parent =>
      F.flatMap(parent.state.get) { s =>
        if (s.open) F.pure(parent)
        else parent.openAncestor
      }
    }

  /** Gets all ancestors of this scope, inclusive of root scope. **/
  private def ancestors: Chain[CompileScope[F]] = {
    @tailrec
    def go(curr: CompileScope[F], acc: Chain[CompileScope[F]]): Chain[CompileScope[F]] =
      curr.parent match {
        case Some(parent) => go(parent, acc :+ parent)
        case None         => acc
      }
    go(self, Chain.empty)
  }

  /** finds ancestor of this scope given `scopeId` **/
  def findSelfOrAncestor(scopeId: Token): Option[CompileScope[F]] = {
    @tailrec
    def go(curr: CompileScope[F]): Option[CompileScope[F]] =
      if (curr.id == scopeId) Some(curr)
      else
        curr.parent match {
          case Some(scope) => go(scope)
          case None        => None
        }
    go(self)
  }

  /** finds scope in child hierarchy of current scope **/
  def findSelfOrChild(scopeId: Token): F[Option[CompileScope[F]]] = {
    def go(scopes: Chain[CompileScope[F]]): F[Option[CompileScope[F]]] =
      scopes.uncons match {
        case None => F.pure(None)
        case Some((scope, tail)) =>
          if (scope.id == scopeId) F.pure(Some(scope))
          else {
            F.flatMap(scope.state.get) { s =>
              if (s.children.isEmpty) go(tail)
              else
                F.flatMap(go(s.children)) {
                  case None        => go(tail)
                  case Some(scope) => F.pure(Some(scope))
                }
            }
          }
      }
    if (self.id == scopeId) F.pure(Some(self))
    else
      F.flatMap(state.get) { s =>
        go(s.children)
      }
  }

  /**
    * Tries to locate scope for the step.
    * It is good chance, that scope is either current scope or the sibling of current scope.
    * As such the order of search is:
    * - check if id is current scope,
    * - check if id is parent or any of its children
    * - traverse all known scope ids, starting from the root.
    *
    */
  def findStepScope(scopeId: Token): F[Option[CompileScope[F]]] = {
    @tailrec
    def go(scope: CompileScope[F]): CompileScope[F] =
      scope.parent match {
        case None         => scope
        case Some(parent) => go(parent)
      }

    if (scopeId == self.id) F.pure(Some(self))
    else {
      self.parent match {
        case None => self.findSelfOrChild(scopeId)
        case Some(parent) =>
          F.flatMap(parent.findSelfOrChild(scopeId)) {
            case Some(scope) => F.pure(Some(scope))
            case None        => go(self).findSelfOrChild(scopeId)
          }
      }
    }
  }

  // See docs on [[Scope#lease]]
  def lease: F[Option[Scope.Lease[F]]] =
    F.flatMap(state.get) { s =>
      if (!s.open) F.pure(None)
      else {
        val allScopes = (s.children :+ self) ++ ancestors
        F.flatMap(Traverse[Chain].flatTraverse(allScopes)(_.resources)) { allResources =>
          F.map(TraverseFilter[Chain].traverseFilter(allResources) { r =>
            r.lease
          }) { allLeases =>
            val lease = new Scope.Lease[F] {
              def cancel: F[Either[Throwable, Unit]] =
                traverseError[Scope.Lease[F]](allLeases, _.cancel)
            }
            Some(lease)
          }
        }
      }
    }

  // See docs on [[Scope#interrupt]]
  def interrupt(cause: Either[Throwable, Unit]): F[Unit] =
    interruptible match {
      case None =>
        F.raiseError(
          new IllegalStateException("Scope#interrupt called for Scope that cannot be interrupted"))
      case Some(iCtx) =>
        // note that we guard interruption here by Attempt to prevent failure on multiple sets.
        val interruptCause = cause.map(_ => iCtx.interruptRoot)
        F.guarantee(iCtx.deferred.complete(interruptCause)) {
          iCtx.ref.update { _.orElse(Some(interruptCause)) }
        }
    }

  /**
    * Checks if current scope is interrupted.
    * If yields to None, scope is not interrupted and evaluation may normally proceed.
    * If yields to Some(Right(scope,next)) that yields to next `scope`, that has to be run and `next`  stream
    * to evaluate
    */
  def isInterrupted: F[Option[Either[Throwable, Token]]] =
    interruptible match {
      case None       => F.pure(None)
      case Some(iCtx) => iCtx.ref.get
    }

  /**
    * When the stream is evaluated, there may be `Eval` that needs to be cancelled early,
    * when scope allows interruption.
    * Instead of just allowing eval to complete, this will race between eval and interruption promise.
    * Then, if eval completes without interrupting, this will return on `Right`.
    *
    * However when the evaluation is normally interrupted the this evaluates on `Left` - `Right` where we signal
    * what is the next scope from which we should calculate the next step to take.
    *
    * Or if the evaluation is interrupted by a failure this evaluates on `Left` - `Left` where the exception
    * that caused the interruption is returned so that it can be handled.
    */
  private[internal] def interruptibleEval[A](f: F[A]): F[Either[Either[Throwable, Token], A]] =
    interruptible match {
      case None => F.map(F.attempt(f)) { _.swap.map(Left(_)).swap }
      case Some(iCtx) =>
        F.map(
          iCtx.concurrent
            .race(iCtx.deferred.get, F.attempt(f))) {
          case Right(result) => result.leftMap(Left(_))
          case Left(other)   => Left(other)
        }
    }

  override def toString =
    s"RunFoldScope(id=$id,interruptible=${interruptible.nonEmpty})"
}

private[fs2] object CompileScope {

  /** Creates a new root scope. */
  def newRoot[F[_]: Sync]: F[CompileScope[F]] =
    Sync[F].delay(new CompileScope[F](new Token(), None, None))

  /**
    * State of a scope.
    *
    * @param open               Yields to true if the scope is open
    *
    * @param resources          All acquired resources (that means synchronously, or the ones acquired asynchronously) are
    *                           registered here. Note that the resources are prepended when acquired, to be released in reverse
    *                           order s they were acquired.
    *
    * @param children           Children of this scope. Children may appear during the parallel pulls where one scope may
    *                           split to multiple asynchronously acquired scopes and resources.
    *                           Still, likewise for resources they are released in reverse order.
    *
    */
  final private case class State[F[_]](
      open: Boolean,
      resources: Chain[Resource[F]],
      children: Chain[CompileScope[F]]
  ) { self =>

    def unregisterChild(id: Token): State[F] =
      self.children.deleteFirst(_.id == id) match {
        case Some((_, newChildren)) => self.copy(children = newChildren)
        case None                   => self
      }

    def close: State[F] = CompileScope.State.closed
  }

  private object State {
    private val initial_ =
      State[Pure](open = true, resources = Chain.empty, children = Chain.empty)
    def initial[F[_]]: State[F] = initial_.asInstanceOf[State[F]]

    private val closed_ =
      State[Pure](open = false, resources = Chain.empty, children = Chain.empty)
    def closed[F[_]]: State[F] = closed_.asInstanceOf[State[F]]
  }

  /**
    * A context of interruption status. This is shared from the parent that was created as interruptible to all
    * its children. It assures consistent view of the interruption through the stack
    * @param concurrent   Concurrent, used to create interruption at Eval.
    *                 If signalled with None, normal interruption is signalled. If signaled with Some(err) failure is signalled.
    * @param ref      When None, scope is not interrupted,
    *                 when Some(None) scope was interrupted, and shall continue with `whenInterrupted`
    *                 when Some(Some(err)) scope has to be terminated with supplied failure.
    * @param interruptRoot Id of the scope that is root of this interruption and is guaranteed to be a parent of this scope.
    *                      Once interrupted, this scope must be closed and `FreeC` must be signalled to provide recovery of the interruption.
    * @param cancelParent  Cancels listening on parent's interrupt.
    */
  final private[internal] case class InterruptContext[F[_]](
      concurrent: Concurrent[F],
      deferred: Deferred[F, Either[Throwable, Token]],
      ref: Ref[F, Option[Either[Throwable, Token]]],
      interruptRoot: Token,
      cancelParent: F[Unit]
  ) { self =>

    /**
      * Creates a [[InterruptContext]] for a child scope which can be interruptible as well.
      *
      * In case the child scope is interruptible, this will ensure that this scope interrupt will
      * interrupt the child scope as well.
      *
      * In any case this will make sure that a close of the child scope will not cancel listening
      * on parent interrupt for this scope.
      *
      * @param interruptible  Whether the child scope should be interruptible.
      * @param newScopeId     The id of the new scope.
      */
    def childContext(
        interruptible: Option[Concurrent[F]],
        newScopeId: Token
    )(implicit F: Sync[F]): F[InterruptContext[F]] =
      interruptible
        .map { concurent =>
          F.flatMap(concurrent.start(self.deferred.get)) { fiber =>
            val context = InterruptContext[F](
              concurrent = concurrent,
              deferred = Deferred.unsafe[F, Either[Throwable, Token]](concurrent),
              ref = Ref.unsafe[F, Option[Either[Throwable, Token]]](None),
              interruptRoot = newScopeId,
              cancelParent = fiber.cancel
            )

            F.map(concurrent.start(F.flatMap(fiber.join)(interrupt =>
              F.flatMap(context.ref.update(_.orElse(Some(interrupt)))) { _ =>
                F.map(F.attempt(context.deferred.complete(interrupt)))(_ => ())
            }))) { _ =>
              context
            }
          }
        }
        .getOrElse(F.pure(copy(cancelParent = F.unit)))

  }

  private object InterruptContext {

    /**
      * Creates a new interrupt context for a new scope if the scope is interruptible.
      *
      * This is UNSAFE method as we are creating promise and ref directly here.
      *
      * @param interruptible  Whether the scope is interruptible by providing effect, execution context and the
      *                       continuation in case of interruption.
      * @param newScopeId     The id of the new scope.
      */
    def unsafeFromInterruptible[F[_]](
        interruptible: Option[Concurrent[F]],
        newScopeId: Token
    )(implicit F: Sync[F]): Option[InterruptContext[F]] =
      interruptible.map { concurrent =>
        InterruptContext[F](
          concurrent = concurrent,
          deferred = Deferred.unsafe[F, Either[Throwable, Token]](concurrent),
          ref = Ref.unsafe[F, Option[Either[Throwable, Token]]](None),
          interruptRoot = newScopeId,
          cancelParent = F.unit
        )
      }

  }
}
