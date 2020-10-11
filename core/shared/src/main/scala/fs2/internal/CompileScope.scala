/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.internal

import scala.annotation.tailrec

import cats.{Applicative, Id, Traverse, TraverseFilter}
import cats.data.Chain
import cats.effect.{Concurrent, Outcome, Poll, Resource}
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.implicits._
import cats.syntax.all._

import fs2.{Compiler, CompositeFailure, Pure, Scope}
import fs2.internal.CompileScope.{InterruptContext, InterruptionOutcome}

/** Implementation of [[Scope]] for the internal stream interpreter.
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
  * via `Stream.bracket`. See [[ScopedResource]] docs for more information.
  *
  * @param id              Unique identification of the scope
  * @param parent          If empty indicates root scope. If non-empty, indicates parent of this scope.
  * @param interruptible   If defined, allows this scope to interrupt any of its operation. Interruption
  *                       is performed using the supplied context.
  *                       Normally the interruption awaits next step in Algebra to be evaluated, with exception
  *                       of Eval, that when interruption is enabled on scope will be wrapped in race,
  *                       that eventually allows interruption while eval is evaluating.
  */
private[fs2] final class CompileScope[F[_]] private (
    val id: Token,
    val parent: Option[CompileScope[F]],
    val interruptible: Option[InterruptContext[F]],
    private val state: Ref[F, CompileScope.State[F]]
)(implicit val F: Compiler.Target[F])
    extends Scope[F] { self =>

  /** Registers supplied resource in this scope.
    * Returns false and makes no registration if this scope has been closed.
    */
  private def register(resource: ScopedResource[F]): F[Boolean] =
    state.modify {
      case s: CompileScope.State.Open[F]   => (s.copy(resources = resource +: s.resources), true)
      case s: CompileScope.State.Closed[F] => (s, false)
    }

  /** Opens a child scope.
    *
    * If this scope is currently closed, then the child scope is opened on the first
    * open ancestor of this scope.
    *
    * Returns scope that has to be used in next compilation step.
    */
  def open(
      interruptible: Option[Interruptible[F]]
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
    val createCompileScope: F[CompileScope[F]] = Token[F].flatMap { newScopeId =>
      self.interruptible match {
        case None =>
          val fiCtx = interruptible.traverse(i => InterruptContext(i, newScopeId, F.unit))
          fiCtx.flatMap(iCtx => CompileScope[F](newScopeId, Some(self), iCtx))

        case Some(parentICtx) =>
          parentICtx.childContext(interruptible, newScopeId).flatMap { iCtx =>
            CompileScope[F](newScopeId, Some(self), Some(iCtx))
          }
      }
    }

    createCompileScope.flatMap { scope =>
      state
        .modify {
          case s: CompileScope.State.Closed[F] => (s, None)
          case s: CompileScope.State.Open[F] =>
            (s.copy(children = scope +: s.children), Some(scope))
        }
        .flatMap {
          case Some(s) => F.pure(Right(s))
          case None    =>
            // This scope is already closed so try to promote the open to an ancestor; this can fail
            // if the root scope has already been closed, in which case, we can safely throw
            self.parent match {
              case Some(parent) =>
                self.interruptible.map(_.cancelParent).getOrElse(F.unit) >> parent.open(
                  interruptible
                )

              case None =>
                F.pure(Left(new IllegalStateException("cannot re-open root scope")))
            }
        }
    }
  }

  /** fs2 Stream is interpreted synchronously, as such the resource acquisition is fully synchronous.
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
      fr: Poll[F] => F[R],
      release: (R, Resource.ExitCase) => F[Unit]
  ): F[Either[Throwable, R]] =
    ScopedResource.create[F].flatMap { resource =>
      F.uncancelable { poll =>
        fr(poll).redeemWith(
          t => F.pure(Left(t)),
          r => {
            val finalizer = (ec: Resource.ExitCase) => release(r, ec)
            resource.acquired(finalizer).flatMap { result =>
              if (result.exists(identity)) {
                register(resource).flatMap {
                  case false =>
                    finalizer(Resource.ExitCase.Canceled).as(Left(AcquireAfterScopeClosed))
                  case true => F.pure(Right(r))
                }
              } else {
                finalizer(Resource.ExitCase.Canceled)
                  .as(Left(result.swap.getOrElse(AcquireAfterScopeClosed)))
              }
            }
          }
        )
      }
    }

  /** Unregisters the child scope identified by the supplied id.
    *
    * As a result of unregistering a child scope, its resources are no longer
    * reachable from its parent.
    */
  private def releaseChildScope(id: Token): F[Unit] =
    state.update {
      case s: CompileScope.State.Open[F]   => s.unregisterChild(id)
      case s: CompileScope.State.Closed[F] => s
    }

  /** Returns all direct resources of this scope (does not return resources in ancestor scopes or child scopes). * */
  private def resources: F[Chain[ScopedResource[F]]] =
    state.get.map {
      case s: CompileScope.State.Open[F]   => s.resources
      case s: CompileScope.State.Closed[F] => Chain.empty
    }

  /** Traverses supplied `Chain` with `f` that may produce a failure, and collects these failures.
    * Returns failure with collected failures, or `Unit` on successful traversal.
    */
  private def traverseError[A](
      ca: Chain[A],
      f: A => F[Either[Throwable, Unit]]
  ): F[Either[Throwable, Unit]] =
    Traverse[Chain].traverse(ca)(f).map { results =>
      CompositeFailure
        .fromList(results.collect { case Left(err) => err }.toList)
        .toLeft(())
    }

  /** Closes this scope.
    *
    * All resources of this scope are released when this is evaluated.
    *
    * Also this will close the child scopes (if any) and release their resources.
    *
    * If this scope has a parent scope, this scope will be unregistered from its parent.
    *
    * Note that if there were leased or not yet acquired resources, these resource will not yet be
    * finalized after this scope is closed, but they will get finalized shortly after. See [[ScopedResource]] for
    * more details.
    */
  def close(ec: Resource.ExitCase): F[Either[Throwable, Unit]] =
    state.modify(s => CompileScope.State.closed -> s).flatMap {
      case previous: CompileScope.State.Open[F] =>
        for {
          resultChildren <- traverseError[CompileScope[F]](previous.children, _.close(ec))
          resultResources <- traverseError[ScopedResource[F]](previous.resources, _.release(ec))
          _ <- self.interruptible.map(_.cancelParent).getOrElse(F.unit)
          _ <- self.parent.fold(F.unit)(_.releaseChildScope(self.id))
        } yield {
          val results = resultChildren.fold(List(_), _ => Nil) ++ resultResources.fold(
            List(_),
            _ => Nil
          )
          CompositeFailure.fromList(results.toList).toLeft(())
        }
      case _: CompileScope.State.Closed[F] => F.pure(Right(()))
    }

  /** Returns closest open parent scope or root. */
  def openAncestor: F[CompileScope[F]] =
    self.parent.fold(F.pure(self)) { parent =>
      parent.state.get.flatMap {
        case _: CompileScope.State.Open[F]   => F.pure(parent)
        case _: CompileScope.State.Closed[F] => parent.openAncestor
      }
    }

  /** Gets all ancestors of this scope, inclusive of root scope. * */
  private def ancestors: Chain[CompileScope[F]] = {
    @tailrec
    def go(curr: CompileScope[F], acc: Chain[CompileScope[F]]): Chain[CompileScope[F]] =
      curr.parent match {
        case Some(parent) => go(parent, acc :+ parent)
        case None         => acc
      }
    go(self, Chain.empty)
  }

  /** finds ancestor of this scope given `scopeId` * */
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

  /** finds scope in child hierarchy of current scope * */
  def findSelfOrChild(scopeId: Token): F[Option[CompileScope[F]]] = {
    def go(scopes: Chain[CompileScope[F]]): F[Option[CompileScope[F]]] =
      scopes.uncons match {
        case None => F.pure(None)
        case Some((scope, tail)) =>
          if (scope.id == scopeId) F.pure(Some(scope))
          else
            scope.state.get.flatMap {
              case s: CompileScope.State.Open[F] =>
                if (s.children.isEmpty) go(tail)
                else
                  go(s.children).flatMap {
                    case None        => go(tail)
                    case Some(scope) => F.pure(Some(scope))
                  }
              case s: CompileScope.State.Closed[F] => go(tail)
            }
      }
    if (self.id == scopeId) F.pure(Some(self))
    else
      state.get.flatMap {
        case s: CompileScope.State.Open[F]   => go(s.children)
        case s: CompileScope.State.Closed[F] => F.pure(None)
      }
  }

  /** Tries to locate scope for the step.
    * It is good chance, that scope is either current scope or the sibling of current scope.
    * As such the order of search is:
    * - check if id is current scope,
    * - check if id is parent or any of its children
    * - traverse all known scope ids, starting from the root.
    */
  def findStepScope(scopeId: Token): F[Option[CompileScope[F]]] = {
    @tailrec
    def go(scope: CompileScope[F]): CompileScope[F] =
      scope.parent match {
        case None         => scope
        case Some(parent) => go(parent)
      }

    if (scopeId == self.id) F.pure(Some(self))
    else
      self.parent match {
        case None => self.findSelfOrChild(scopeId)
        case Some(parent) =>
          parent.findSelfOrChild(scopeId).flatMap {
            case Some(scope) => F.pure(Some(scope))
            case None        => go(self).findSelfOrChild(scopeId)
          }
      }
  }

  // See docs on [[Scope#lease]]
  def lease: F[Option[Scope.Lease[F]]] =
    state.get.flatMap {
      case s: CompileScope.State.Open[F] =>
        val allScopes = (s.children :+ self) ++ ancestors
        Traverse[Chain].flatTraverse(allScopes)(_.resources).flatMap { allResources =>
          TraverseFilter[Chain].traverseFilter(allResources)(r => r.lease).map { allLeases =>
            val lease = new Scope.Lease[F] {
              def cancel: F[Either[Throwable, Unit]] =
                traverseError[Scope.Lease[F]](allLeases, _.cancel)
            }
            Some(lease)
          }
        }
      case s: CompileScope.State.Closed[F] => F.pure(None)
    }

  // See docs on [[Scope#interrupt]]
  def interrupt(cause: Either[Throwable, Unit]): F[Unit] =
    interruptible match {
      case None =>
        F.raiseError(
          new IllegalStateException("Scope#interrupt called for Scope that cannot be interrupted")
        )
      case Some(iCtx) =>
        val outcome: InterruptionOutcome = cause.fold(
          t => Outcome.Errored(t),
          _ => Outcome.Succeeded[Id, Throwable, Token](iCtx.interruptRoot)
        )
        iCtx.complete(outcome)
    }

  /** Checks if current scope is interrupted.
    * If yields to None, scope is not interrupted and evaluation may normally proceed.
    * If yields to Some(Right(scope,next)) that yields to next `scope`, that has to be run and `next`  stream
    * to evaluate
    */
  def isInterrupted: F[Option[InterruptionOutcome]] =
    interruptible match {
      case None       => F.pure(None)
      case Some(iCtx) => iCtx.ref.get
    }

  /** When the stream is evaluated, there may be `Eval` that needs to be cancelled early,
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
  private[fs2] def interruptibleEval[A](f: F[A]): F[Either[InterruptionOutcome, A]] =
    interruptible match {
      case None =>
        f.attempt.map(_.leftMap(t => Outcome.Errored(t)))
      case Some(iCtx) =>
        iCtx.Concurrent.race(iCtx.deferred.get, f.attempt).map {
          case Right(result) => result.leftMap(Outcome.Errored(_))
          case Left(other)   => Left(other)
        }
    }

  override def toString =
    s"CompileScope(id=$id,interruptible=${interruptible.nonEmpty})"
}

private[fs2] object CompileScope {

  type InterruptionOutcome = Outcome[Id, Throwable, Token]

  private def apply[F[_]](
      id: Token,
      parent: Option[CompileScope[F]],
      interruptible: Option[InterruptContext[F]]
  )(implicit F: Compiler.Target[F]): F[CompileScope[F]] =
    F.ref(CompileScope.State.initial[F])
      .map(state => new CompileScope[F](id, parent, interruptible, state))

  /** Creates a new root scope. */
  def newRoot[F[_]: Compiler.Target]: F[CompileScope[F]] =
    Token[F].flatMap(apply[F](_, None, None))

  private sealed trait State[F[_]]
  private object State {

    /** @param resources          All acquired resources (that means synchronously, or the ones acquired asynchronously) are
      *                           registered here. Note that the resources are prepended when acquired, to be released in reverse
      *                           order s they were acquired.
      *
      * @param children           Children of this scope. Children may appear during the parallel pulls where one scope may
      *                           split to multiple asynchronously acquired scopes and resources.
      *                           Still, likewise for resources they are released in reverse order.
      */
    case class Open[F[_]](resources: Chain[ScopedResource[F]], children: Chain[CompileScope[F]])
        extends State[F] { self =>
      def unregisterChild(id: Token): State[F] =
        self.children.deleteFirst(_.id == id) match {
          case Some((_, newChildren)) => self.copy(children = newChildren)
          case None                   => self
        }
    }
    case class Closed[F[_]]() extends State[F]

    private val initial_ =
      Open[Nothing](resources = Chain.empty, children = Chain.empty)
    def initial[F[_]]: State[F] = initial_.asInstanceOf[State[F]]

    private val closed_ = Closed[Nothing]()
    def closed[F[_]]: State[F] = closed_.asInstanceOf[State[F]]
  }

  /** A context of interruption status. This is shared from the parent that was created as interruptible to all
    * its children. It assures consistent view of the interruption through the stack
    * @param concurrent   Concurrent, used to create interruption at Eval.
    *                 If signalled with None, normal interruption is signalled. If signaled with Some(err) failure is signalled.
    * @param ref      When None, scope is not interrupted,
    *                 when Some(None) scope was interrupted, and shall continue with `whenInterrupted`
    *                 when Some(Some(err)) scope has to be terminated with supplied failure.
    * @param interruptRoot Id of the scope that is root of this interruption and is guaranteed to be a parent of this scope.
    *                      Once interrupted, this scope must be closed and pull must be signalled to provide recovery of the interruption.
    * @param cancelParent  Cancels listening on parent's interrupt.
    */
  final private[internal] case class InterruptContext[F[_]](
      deferred: Deferred[F, InterruptionOutcome],
      ref: Ref[F, Option[InterruptionOutcome]],
      interruptRoot: Token,
      cancelParent: F[Unit]
  )(implicit val Concurrent: Concurrent[F]) { self =>

    def complete(outcome: InterruptionOutcome): F[Unit] =
      ref.update(_.orElse(Some(outcome))).guarantee(deferred.complete(outcome).void)

    /** Creates a [[InterruptContext]] for a child scope which can be interruptible as well.
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
        interruptible: Option[Interruptible[F]],
        newScopeId: Token
    ): F[InterruptContext[F]] =
      interruptible
        .map { inter =>
          self.deferred.get.start.flatMap { fiber =>
            InterruptContext(inter, newScopeId, fiber.cancel).flatMap { context =>
              fiber.join
                .flatMap {
                  case Outcome.Succeeded(interrupt) =>
                    interrupt.flatMap(i => context.complete(i))
                  case Outcome.Errored(t) =>
                    context.complete(Outcome.Errored(t))
                  case Outcome.Canceled() =>
                    context.complete(Outcome.Canceled())
                }
                .start
                .as(context)
            }
          }
        }
        .getOrElse(copy(cancelParent = Applicative[F].unit).pure[F])
  }

  private object InterruptContext {

    def apply[F[_]](
        interruptible: Interruptible[F],
        newScopeId: Token,
        cancelParent: F[Unit]
    ): F[InterruptContext[F]] = {
      import interruptible._
      for {
        ref <- Concurrent[F].ref[Option[InterruptionOutcome]](None)
        deferred <- Concurrent[F].deferred[InterruptionOutcome]
      } yield InterruptContext[F](
        deferred = deferred,
        ref = ref,
        interruptRoot = newScopeId,
        cancelParent = cancelParent
      )
    }
  }
}
