package fs2.internal


import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicReference

import fs2.{Catenable, CompositeFailure, Interrupted, Lease, Scope}
import fs2.async.{Promise, Ref}
import cats.effect.{Effect, Sync}

import scala.concurrent.ExecutionContext

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
 * @param parent          If empty indicates root scope. If non-emtpy, indicates parent of this scope.
 * @param interruptible   If defined, allows this scope to interrupt any of its operation. Interruption
  *                       is performed using the supplied Effect instance and execution context.
  *                       Normally the interruption awaits next step in Algebra to be evaluated, with exception
  *                       of Eval, that when interruption is enabled on scope will be wrapped in race,
  *                       that eventually allows interruption while eval is evaluating.
  *
 */
private[fs2] final class RunFoldScope[F[_]] private (
  val id: Token
  , private val parent: Option[RunFoldScope[F]]
  , val interruptible: Option[(Effect[F], ExecutionContext, Promise[F, Throwable], Ref[F, (Option[Throwable], Boolean)])]
)(implicit F: Sync[F]) extends Scope[F] { self =>

  private val state: Ref[F, RunFoldScope.State[F]] = new Ref(new AtomicReference(RunFoldScope.State.initial))


  /**
   * Registers supplied resource in this scope.
   * Returns false if the resource may not be registered because scope is closed already.
   */
  def register(resource: Resource[F]): F[Boolean] =
    F.map(state.modify { s =>
      if (!s.open) s
      else s.copy(resources = resource +: s.resources)
    })(_.now.open)


  /**
   * Releases the resource identified by the supplied token.
   *
   * Invoked when a resource is released during the scope's lifetime.
   * When the action returns, the resource may not be released yet, as
   * it may have been `leased` to other scopes.
   */
  def releaseResource(id: Token): F[Either[Throwable, Unit]] =
    F.flatMap(state.modify2 {  _.unregisterResource(id) }) { case (c, mr) => mr match {
      case Some(resource) => resource.release
      case None => F.pure(Right(()))// resource does not exist in scope any more.
    }}


  /**
   * Opens a child scope.
   *
   * If this scope is currently closed, then the child scope is opened on the first
   * open ancestor of this scope.
   */
  def open(interruptible: Option[(Effect[F], ExecutionContext, Promise[F, Throwable], Ref[F, (Option[Throwable], Boolean)])]): F[RunFoldScope[F]] = {
    F.flatMap(state.modify2 { s =>
      if (! s.open) (s, None)
      else {
        val scope = new RunFoldScope[F](new Token(), Some(self), interruptible orElse self.interruptible)
        (s.copy(children = scope +: s.children), Some(scope))
      }
    }) {
      case (c, Some(s)) => F.pure(s)
      case (_, None) =>
        // This scope is already closed so try to promote the open to an ancestor; this can fail
        // if the root scope has already been closed, in which case, we can safely throw
        self.parent match {
          case Some(parent) => parent.open(interruptible)
          case None => F.raiseError(throw new IllegalStateException("cannot re-open root scope"))
        }
    }
  }

  /**
   * Unregisters the child scope identified by the supplied id.
   *
   * As a result of unregistering a child scope, its resources are no longer
   * reachable from its parent.
   */
  def releaseChildScope(id: Token): F[Unit] =
    F.map(state.modify2 { _.unregisterChild(id) }){ _ => () }


  /** Returns all direct resources of this scope (does not return resources in ancestor scopes or child scopes). **/
  def resources: F[Catenable[Resource[F]]] = {
    F.map(state.get) { _.resources }
  }

  /**
   * Traverses supplied `Catenable` with `f` that may produce a failure, and collects these failures.
   * Returns failure with collected failures, or `Unit` on successful traversal.
   */
  private def traverseError[A](ca: Catenable[A], f: A => F[Either[Throwable,Unit]]) : F[Either[Throwable, Unit]] = {
    F.map(Catenable.instance.traverse(ca)(f)) { results =>
      CompositeFailure.fromList(results.collect { case Left(err) => err }.toList).toLeft(())
    }
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
  def close: F[Either[Throwable, Unit]] = {
    F.flatMap(state.modify{ _.close }) { c =>
    F.flatMap(traverseError[RunFoldScope[F]](c.previous.children, _.close)) { resultChildren =>
    F.flatMap(traverseError[Resource[F]](c.previous.resources, _.release)) { resultResources =>
    F.map(self.parent.fold(F.unit)(_.releaseChildScope(self.id))) { _ =>
      val results = resultChildren.left.toSeq ++ resultResources.left.toSeq
      CompositeFailure.fromList(results.toList).toLeft(())
    }}}}
  }


  /** Returns closest open parent scope or root. */
  def openAncestor: F[RunFoldScope[F]] = {
    self.parent.fold(F.pure(self)) { parent =>
      F.flatMap(parent.state.get) { s =>
        if (s.open) F.pure(parent)
        else parent.openAncestor
      }
    }
  }

  /** Gets all ancestors of this scope, inclusive of root scope. **/
  private def ancestors: F[Catenable[RunFoldScope[F]]] = {
    @tailrec
    def go(curr: RunFoldScope[F], acc: Catenable[RunFoldScope[F]]): F[Catenable[RunFoldScope[F]]] = {
      curr.parent match {
        case Some(parent) => go(parent, acc :+ parent)
        case None => F.pure(acc)
      }
    }
    go(self, Catenable.empty)
  }

  // See docs on [[Scope#lease]]
  def lease: F[Option[Lease[F]]] = {
    val T = Catenable.instance
    F.flatMap(state.get) { s =>
      if (!s.open) F.pure(None)
      else {
        F.flatMap(T.traverse(s.children :+ self)(_.resources)) { childResources =>
        F.flatMap(ancestors) { anc =>
        F.flatMap(T.traverse(anc) { _.resources }) { ancestorResources =>
          val allLeases = childResources.flatMap(identity) ++ ancestorResources.flatMap(identity)
          F.map(T.traverse(allLeases) { r => r.lease }) { leased =>
            val allLeases = leased.collect { case Some(resourceLease) => resourceLease }
            val lease = new Lease[F] {
              def cancel: F[Either[Throwable, Unit]] = traverseError[Lease[F]](allLeases, _.cancel)
            }
            Some(lease)
          }
        }}}
      }
    }
  }

  // See docs on [[Scope#interrupt]]
  def interrupt(cause: Either[Throwable, Unit]): F[Unit] = {
    interruptible match {
      case None => F.raiseError(new Throwable("Scope#interrupt called for Scope that cannot be interrupted"))
      case Some((_, _, promise, ref)) =>
        val interruptRsn  = cause.left.toOption.getOrElse(Interrupted)
        F.flatMap(F.attempt(promise.complete(interruptRsn))) {
          case Right(_) =>
            F.map(ref.modify({ case (interrupted, signalled) => (interrupted orElse Some(interruptRsn), signalled)})) { _ => ()}
          case Left(_) =>
            F.unit
        }

    }

  }

  /**
    * If evaluates to Some(rsn) then the current step evaluation in stream shall be interrupted by
    * given reason. Also sets the `interrupted` flag, so this is guaranteed to yield to interrupt only once.
    *
    * Used when interruption stream in pure steps between `uncons`
    */
  def shallInterrupt: F[Option[Throwable]] = {
    interruptible match {
      case None => F.pure(None)
      case Some((_, _, _, ref)) =>
        F.flatMap(ref.get) { case (interrupted, signalled) =>
          if (signalled || interrupted.isEmpty) F.pure(None)
          else {
            F.map(ref.modify { case (int, _) => (int, true)}) { c =>
              if (c.previous._2) None
              else c.previous._1
            }
          }
        }
    }
  }


  /**
    * When the stream is evaluated, there may be `Eval` that needs to be cancelled early, when asynchronous interruption
    * is taking the place.
    * This allows to augment eval so whenever this scope is interupted it will return on left the reason of interruption.
    * If the eval completes before the scope is interrupt, then this will return `A`.
    */
  private[internal] def interruptibleEval[A](f: F[A]): F[Either[Throwable, A]] = {
    interruptible match {
      case None => F.attempt(f)
      case Some((effect, ec, promise, ref)) =>
        F.flatMap(ref.get) { case (_, signalled) =>
          if (signalled) F.attempt(f)
          else {
            // we assume there will be no parallel evals/interpretation in the scope.
            // the concurrent stream evaluation (like merge, join) shall have independent interruption
            // for every asynchronous child scope of the stream, so this assumption shall be completely safe.
            F.flatMap(promise.cancellableGet) { case (get, cancel) =>
              F.flatMap(fs2.async.race(get, F.attempt(f))(effect, ec)) {
                case Right(result) => F.map(cancel)(_ => result)
                case Left(err) =>
                  // this indicates that we have consumed signalling the interruption
                  // there is only one interruption allowed per scope.
                  // as such, we have to set interruption flag
                  F.map(ref.modify { case (int, sig) => (int, true) })(_ => Left(err))
              }}
          }
        }
    }

  }

  override def toString = s"RunFoldScope(id=$id,interruptible=${interruptible.nonEmpty})"
}

private[internal] object RunFoldScope {
  /** Creates a new root scope. */
  def newRoot[F[_]: Sync]: RunFoldScope[F] = new RunFoldScope[F](new Token(), None, None)

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
    open: Boolean
    , resources: Catenable[Resource[F]]
    , children: Catenable[RunFoldScope[F]]
  ) { self =>

    def unregisterResource(id: Token): (State[F], Option[Resource[F]]) = {
      self.resources.deleteFirst(_.id == id).fold((self, None: Option[Resource[F]])) { case (r, c) =>
        (self.copy(resources = c), Some(r))
      }
    }

    def unregisterChild(id: Token): (State[F], Option[RunFoldScope[F]]) = {
      self.children.deleteFirst(_.id == id).fold((self, None: Option[RunFoldScope[F]])) { case (s, c) =>
        (self.copy(children = c), Some(s))
      }
    }

    def close: State[F] = RunFoldScope.State.closed
  }

  private object State {
    private val initial_ = State[Nothing](open = true, resources = Catenable.empty, children = Catenable.empty)
    def initial[F[_]]: State[F] = initial_.asInstanceOf[State[F]]

    private val closed_ = State[Nothing](open = false, resources = Catenable.empty, children = Catenable.empty)
    def closed[F[_]]: State[F] = closed_.asInstanceOf[State[F]]
  }
}
