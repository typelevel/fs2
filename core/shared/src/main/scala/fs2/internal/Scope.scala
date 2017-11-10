package fs2.internal

import fs2.{Catenable, async}
import Algebra.{Resource, Token}
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2.async.Ref.Change
import fs2.internal.Scope.ScopeState

import scala.collection.immutable.ListMap
import scala.concurrent.ExecutionContext

final class Scope[F[_]] private (private val id: Token, private val parent: Option[Scope[F]])(implicit F: Sync[F]) { self =>

  private val state: SyncRef[F, ScopeState[F]] = SyncRef(ScopeState.initial.asInstanceOf[ScopeState[F]])


  /**
    * When asynchronous resource is acquired, this is consulted.
    * Yields to false, if the resource cannot start its acquisition due to scope being closed.
    */
  private[internal]  def beginAcquire: F[Boolean] =
    F.map(state.modify { s =>
      if (s.notOpen_?) s
      else s.copy(refCount = s.refCount + 1)
    })(_.previous.closed.isEmpty)


  /** when asynchronous resource was acquired, then this is consulted, to register resource and it's finalizer **/
  private[internal]  def finishAcquire(t: Token, finalizer: F[Unit]): F[Unit] =
    F.flatMap(state.modify { s =>
      if (s.closed_?) s // ok to proceed when open or when closing, should not be possible when closed already.
      else s.copy(
        refCount = s.refCount - 1
        , resources = s.resources + (t -> Resource(finalizer))
        , refReleased = if (s.refCount == 1) None else s.refReleased
      )
    }) { c =>
      if (c.now.notOpen_?) F.raiseError(new IllegalStateException("FS2 bug: scope cannot be closed while acquire is outstanding"))
      else signalMidAcquiresDone(c)
    }


  /**
    * Cancels asynchronous acquire of the resource. This decrements midAcquired and perfroms any cleanup necessary
    * if all resources were already acquired or their acquisition ws cancelled.
    *
    * @return
    */
  private[internal] def cancelAcquire(): F[Unit] = {
    F.flatMap(state.modify { s =>
      s.copy(
        refCount = s.refCount - 1
        , refReleased = if (s.refCount == 1) None else s.refReleased
      )
    }) { signalMidAcquiresDone }
  }

  /**
    * On cancelAcquire or finishAcquire when the scope is closing and awaiting the acquisition to be completed
    * this will run the finalizer, iff the finalizer is present.
    *
    */
  private def signalMidAcquiresDone(c: Change[ScopeState[F]]): F[Unit] = {
    if (c.now.refCount == 0 && c.previous.refCount == 1) c.previous.refReleased.getOrElse(F.unit)
    else F.unit
  }

  /**
    * When eveluated, will optionally return finalizer for the resource, if that resource was nto closed yet.
    * Note that even when the resource's finalizer is run, this still may not release resource, if that
    * resource was exported to other Scope.
    * @param t
    * @return
    */
  private[internal]  def releaseResource(t: Token): F[Option[F[Unit]]] =
    F.map(state.modify { s => s.copy(resources = s.resources - t)}) { c =>
      c.previous.resources.get(t) map { _.release }
    }

  /**
    * Open child scope of this scope.
    *
    * Children may form linked list, typically for pure Stream that during its execution will not from cmoplex merged
    * hierarchies (i.e. Pipe 2's ). In that case, hierarchy will be simple Stack.
    *
    * However when parallel streams will be part of stream executions, then scopes may be created and released when
    * the streams will start / stop their executions. In that case they will be registered to `children` map.
    *
    *
    *
    */
  private[internal] def open: F[Scope[F]] = {
    F.flatMap(state.modify2 { s =>
      if (s.notOpen_?) (s, None)
      else {
        val scope = new Scope[F](new Token(), Some(self))
        (s.copy(children = s.children + (scope.id -> scope)), Some(scope))
      }
    }) {
      case (c, Some(s)) => F.pure(s)
      case (_, None) =>
        // This scope is already closed so try to promote the open to an ancestor; this can fail
        // if the root scope has already been closed, in which case, we can safely throw
        self.parent match {
          case Some(parent) => parent.open
          case None => F.raiseError(throw new IllegalStateException("cannot re-open root scope"))
        }
    }
  }

  /** When the child scope is closed this is onvoked to unregister scope from parent **/
  private def releaseChildScope(id: Token): F[Unit] =
    F.map(state.modify { s => s.copy(children = s.children - id)}){ _ => ()}


  /**
    * Invoked when All resources were acquired and finalizers are about to be run.
    * Note this is guarded by the `close` flag to be run only once per scope and only as
    * result of `closeAndReturnFinalizers`.
    * It may however be run after ALL refCount will complete their acquisition process.
    */

  private def finishClose(asyncSupport: Option[(Effect[F], ExecutionContext)]): F[Catenable[(Token,F[Unit])]] = {
    F.flatMap(state.modify { _.copy(resources = ListMap(), children = ListMap(), closed = Some(true)) }) { c =>
      import cats.syntax.traverse._
      import cats.syntax.functor._
      import cats.instances.list._

      c.previous.children.toList.reverse.map(_._2)
      .traverse(_.closeAndReturnFinalizers(asyncSupport))
      .map(_.foldLeft(Catenable.empty: Catenable[(Token, F[Unit])])(_ ++ _))
      .flatMap { s =>
        F.map(parent.map(_.releaseChildScope(self.id)).getOrElse(F.unit)) { _ =>
          s ++ Catenable.fromSeq(c.previous.resources.toList.reverse.map { case (t, r) => (t, r.release) })
        }
      }
    }
  }

  /**
    * If the scope is closed (or is about to close), this will return empty resources and finalizers to be run
    * If the scope is open, but there are still some midacquires in progress, this will retrun empty resources and finalizers
    * Else, this will retrun all resources and finalizers of theirs to be run.
    *
    * @param asyncSupport If nonemtpy, this scope was run with asynchronous `F` and release fo this scope may require asynchronous execution.
    * @return
    */
  private def closeAndReturnFinalizers(asyncSupport: Option[(Effect[F], ExecutionContext)]): F[Catenable[(Token,F[Unit])]] = {
    F.flatMap(state.modify { s => s.copy(closed = s.closed orElse Some(false))  }) { c =>
      if (c.previous.notOpen_?) F.pure(Catenable.empty) // done someone else is already taking care of this scope
      else {
        if (c.now.refCount == 0) finishClose(asyncSupport)
        else {
          asyncSupport match {
            case None => F.raiseError(new IllegalStateException(s"FS2 bug: closing a scope with refCount ${c.now.refCount} but no async steps"))
            case Some((effect, ec)) =>
              val ref = new async.Ref[F,Unit]()(effect, ec)

              F.flatMap(state.modify { _.copy(refReleased = Some(ref.setAsyncPure(()))) }) { c =>
                if (c.now.refCount == 0) finishClose(asyncSupport) // refCount were updated before we registered ref signal.
                else F.flatMap(ref.get) { _ => finishClose(asyncSupport) }
              }
          }
        }
      }
    }
  }

  /**
    * Close current scope.
    *
    * This will acquire all child scopes and resources from the child scopes. Once acquired, this will run
    * all finalizers for the scopes.
    *
    * If scope or any of its child scopes are in process of acquiring asynchronous resources, this will wait till
    * the resource allocation is completed or will cancel the resource allocation, and then will run the cleanups for that resources.
    *
    * Returns either failure or next scope that was not yet closed (ancestor), that may be used as current scope.
    *
    * @param asyncSupport If defined, this allows asynchronous execution of cleanup. Always None for pure `F` processes.
    * @return
    */
  private[internal] def close(asyncSupport: Option[(Effect[F],ExecutionContext)]): F[Either[Throwable,Unit]] = {
    def runAll(sofar: Option[Throwable], finalizers: Catenable[F[Unit]]): F[Either[Throwable,Unit]] = finalizers.uncons match {
      case None => F.pure(sofar.toLeft(()))
      case Some((h, t)) => F.flatMap(F.attempt(h)) { res => runAll(sofar orElse res.fold(Some(_), _ => None), t) }
    }
    F.flatMap(closeAndReturnFinalizers(asyncSupport)) { finalizers =>
      runAll(None, finalizers.map(_._2))
    }
  }


  /**
    * Returns closes open parent scope or root.
    */
  private[internal] def openAncestor: F[Scope[F]] = {
    self.parent.fold(F.pure(self)) { parent =>
    F.flatMap(parent.state.get) { s =>
      if (s.open_?) F.pure(parent)
      else parent.openAncestor
    }}
  }


  /**
    * Allows to acquire `this` scope as resource to `remote` stream.
    *
    * Acquisition of the scope prevents this scope to be fully closed (and that means to release its resources) until
    * returned release of the scope is evaluated.
    *
    * This is useful in scenarios, where two streams depends on each other (like Stream#join) and required resources
    * should not to be released unless all dependent streams will release.
    *
    * Linking is implemented as incrementing the `refCount` in `this` scope by one. The `remote` side will
    * then register supplied callback as their normal resource provided returned callback as release of that resource.
    *
    * When this yields to None, that indicates that linking was not succesfull, due `this` scope being closed.
    *
    * Note that acquisition of the scope is synchronous, so it is safe to await its evaluation.
    *
    */
  def acquire: F[Option[F[Unit]]] = {
    F.map(state.modify { s =>
      if (s.notOpen_?) s
      else s.copy(refCount = s.refCount + 1)
    }) { c =>
      if (c.previous.notOpen_?) None
      else Some(cancelAcquire())
    }
  }



}

object Scope {
  def newRoot[F[_]: Sync]: Scope[F] = new Scope[F](new Token(), None)

  /**
    * State of the scope
    *
    * @param closed             Indicates whether the scope is closed. Empty, when scope is open, that means resources
    *                           are about to e acquired (see refCount) or they are being used.
    *                           When Some(true) that indicates scope is fully closed and resources were released. Note that
    *                           they may not have even at that time to be fully finalized, but all users of that resources
    *                           shall fully release them at this point.
    *
    * @param refCount           Contains a reference count which indicates that scope's resources may not start their release
    *                           sequence until this is eq 0.
    *
    *                           When resource is asynchronously constructed this increments, to indicate that resource are being acquired
    *                           but not yet finished their acquisition phase. The resource will decrement this number when either
    *                             (a) the resources finishes its acquisition before scope is closed
    *                             (b) if the acquisition of the resource was interrupted during the scope being closed before that acquisition
    *                                 finished.
    *
    *
    * @param refReleased        A callback  to be invoked, when this close was closed during asynchronous execution or
    *                           before all linked `remote` scopes to this scope were unlinked.
    *                           This must be invoked, when defined and when refCount is decrementing from 0 to 1 and only at that time.
    *
    * @param resources          All acquired resources (that means synchronously, or the ones acquired asynchronously) are
    *                           registered here. Note that the resources are released in reverse order as they were acquired.
    *
    * @param children           Children of this scope. Children may appear during the parallel pulls where one scope may
    *                           split to multiple asynchronously acauired scopes and resources. Still, likewise for resources they are released in reverse order.
    *
    *
    * @tparam F
    */
  final private[Scope] case class ScopeState[F[_]](
    closed: Option[Boolean]
    , refCount: Int
    , refReleased: Option[F[Unit]]
    , resources: ListMap[Token, Resource[F]]
    , children: ListMap[Token, Scope[F]]
  ) {

    def open_? : Boolean = closed.isEmpty
    def notOpen_? : Boolean = closed.nonEmpty
    def closing_? : Boolean = closed.contains(false)
    def closed_? : Boolean = closed.contains(true)

  }


  private[Scope] object ScopeState {
    val initial: ScopeState[Nothing] = ScopeState[Nothing](None, 0, None, ListMap(), ListMap())
  }



}
