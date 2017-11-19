package fs2.internal

import fs2.Catenable
import Algebra.Token
import cats.effect.Sync
import fs2.internal.Scope.ScopeReleaseFailure

import scala.annotation.tailrec



/**
  * Scope represents a controlled block of execution of the stream to track resources acquired and released during
  * the interpretation of the Stream.
  *
  * Scope may have associated resources with it and cleanups, that are evaluated when the scope closes.
  *
  * Scope lifetime:
  *
  * When stream interpretation starts, one `root` scope is created. Then Scopes are created and closed based on the
  * stream structure, but as a rule of thumb every time when you convert Stream to `Pull` and back to Stream you are
  * creating one scope (opening and closing) the scope.
  *
  * So for example `s.chunks` is defined with `s.repeatPull` which in turn is defined with `Pull.loop(...).stream`. In this
  * case you always open and close single Scope.
  *
  * Scopes may be as well opened and closed manually with Stream#scope. Given `s.scope` that will open the scope before
  * the stream `s` to be evaluated and will close the scope once the stream finishes its evaluation.
  *
  * Scope organization
  *
  * Scopes are organized in tree structure, with each scope having at max one parent (root has no parent)
  * with Nil or more child scopes.
  *
  * Every time a new scope is created, it is inheriting parent from the current scope and it is adding itself to be
  * child scope of that parent.
  *
  * During the interpretation of nondeterministic stream (i.e. merge) there may be multiple scopes attached to single parent
  * and these scopes may be created and closed in nondeterministic order.
  *
  * Parent scope never outlives the child scopes. That means when parent scope is closed for whatever reason, the child
  * scopes are closed too.
  *
  * Resources
  *
  * Primary role of the scope is track resource allocation and release. The Stream interpreter guarantees, that
  * resources allocated in the scope are released always when the scope closes.
  *
  * Resource allocation
  *
  * Resource is allocated when interpreter interprets `Acquire` algebra. That is result of the Stream#bracket construct.
  * Please see `Resource` for details how this is done.
  *
  * @param id           Unique identification of the scope
  * @param parent       If empty indicates root scope. If nonemtpy, indicates parent of this scope
  */
final class Scope[F[_]]  private (val id: Token, private val parent: Option[Scope[F]])(implicit F: Sync[F]) { self =>

  private val state: SyncRef[F, Scope.State[F]] = SyncRef(Scope.State.initial)

  /**
    * Registers new resource in this scope.
    * Yields to false, if the resource may not be registered because scope is closed already.
    */
  private[internal] def register(resource: Resource[F]): F[Boolean] =
    F.map(state.modify { s =>
      if (!s.open) s
      else s.copy(resources = resource +: s.resources)
    })(_.now.open)


  /**
    * When resource is released during the Scope lifetime, this is invoked.
    * When this returns, the resource may not be actually released yet, as it may have been `leased` to other scopes,
    * but this scope will lose reference to that resource always when this finishes evaluation.
    */
  private[internal] def releaseResource(id: Token): F[Either[Throwable, Unit]] =
    F.flatMap(state.modify2 {  _.unregisterResource(id) }) { case (c, mr) => mr match {
      case Some(resource) => resource.release
      case None => F.pure(Right(()))// resource does not exist in scope any more.
    }}


  /**
    * Open child scope of this scope.
    *
    * If this scope is currently closed, then this will search any known parent scope of this scope
    * and attaches newly creates scope to that scope.
    *
    */
  private[internal] def open: F[Scope[F]] = {
    F.flatMap(state.modify2 { s =>
      if (! s.open) (s, None)
      else {
        val scope = new Scope[F](new Token(), Some(self))
        (s.copy(children = scope +: s.children), Some(scope))
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

  /**
    * When the child scope is closed, this signals to parent scope that it is not anymore responsible
    * for the children scope and its resources.
    */
  private def releaseChildScope(id: Token): F[Unit] =
    F.map(state.modify2 { _.unregisterChild(id) }){ _ => () }


  /** returns all resources of this scope **/
  private def resources: F[Catenable[Resource[F]]] = {
    F.map(state.get) { _.resources }
  }

  /**
    * Traverses supplued catenable with `f` that may produce failure, and collects theses failures.
    * Returns failure with collected failures, or Unit on succesfull traversal.
    */
  private def traverseError[A](ca: Catenable[A], f: A => F[Either[Throwable,Unit]]) : F[Either[Throwable, Unit]] = {
    F.map(Catenable.traverseInstance.traverse(ca)(f)) { results =>
      results.collect { case Left(err) => err }.uncons match {
        case None => Right(())
        case Some((first, others)) => Left(new ScopeReleaseFailure(first, others))
      }
    }
  }

  /**
    * Closes current scope.
    *
    * All resources of this scope are released when this is evaluated.
    *
    * Also this will close the children scopes (if any) and release their resources.
    *
    * If this scope is child of the parent scope, this will unregister this scope from the parent scope.
    *
    * Note that if there were leased or not yet acquired resources, that may cause that these resource will not be
    * yet finalized even after this scope will be closed, but they will get finalized in near future. See Resource for
    * more details.
    *
    */
  private[internal] def close: F[Either[Throwable, Unit]] = {
    F.flatMap(state.modify{ _.close }) { c =>
    F.flatMap(traverseError[Scope[F]](c.previous.children, _.close)) { resultChildren =>
    F.flatMap(traverseError[Resource[F]](c.previous.resources, _.release)) { resultResources =>
    F.map(self.parent.fold(F.unit)(_.releaseChildScope(self.id))) { _ =>
     Catenable.fromSeq(resultChildren.left.toSeq ++ resultResources.left.toSeq).uncons match {
       case None => Right(())
       case Some((h, t)) => Left(new ScopeReleaseFailure(h, t))
     }
    }}}}
  }


  /**
    * Returns closes open parent scope or root.
    */
  private[internal] def openAncestor: F[Scope[F]] = {
    self.parent.fold(F.pure(self)) { parent =>
      F.flatMap(parent.state.get) { s =>
        if (s.open) F.pure(parent)
        else parent.openAncestor
      }
    }
  }

  /** gets all ancestors of this scope, inclusive root scope **/
  private def ancestors: F[Catenable[Scope[F]]] = {
    @tailrec
    def go(curr: Scope[F], acc: Catenable[Scope[F]]): F[Catenable[Scope[F]]] = {
      curr.parent match {
        case Some(parent) => go(parent, acc :+ parent)
        case None => F.pure(acc)
      }
    }
    go(self, Catenable.empty)
  }


  /**
    * Allows to lease resources of the current scope.
    *
    * Note that this will lease all the resources, and resources of all parents and children of this scope.
    *
    * If this scope is closed already, this will yield to None. Otherwise this returns `F` that when evaluated
    * will cancelLease of the leased resources, possibly invoking their finalization.
    *
    * Resource may be finalized during this being executed, but before `lease` is acquired on the resource.
    * In that case the already finalized resource won't be leased.
    *
    * As such this is important to be run only when all resources are known to be not finalized or not being
    * about to be finalized yet.
    *
    * Wehn this completes all resources available at that time have been successfully leased.
    *
    */
  def lease: F[Option[F[Either[Throwable, Unit]]]] = {
    val T = Catenable.traverseInstance
    def cancel(resources: Catenable[Resource[F]]): F[Either[Throwable, Unit]] = {
      traverseError[Resource[F]](resources, _.cancelLease)
    }

    F.flatMap(state.get) { s =>
      if (!s.open) F.pure(None)
      else {
        F.flatMap(T.traverse(s.children :+ self)(_.resources)) { childResources =>
        F.flatMap(ancestors) { anc =>
        F.flatMap(T.traverse(anc) { _.resources }) { ancestorResources =>
          val allLeases = childResources.flatMap(identity) ++ ancestorResources.flatMap(identity)
          F.map(T.traverse(allLeases) { r => F.map(r.lease)(leased => (r, leased)) }) { leased =>
            Some(cancel(leased.collect { case (r, true) => r }))
          }
        }}}
      }
    }

  }



}

object Scope {
  def newRoot[F[_]: Sync]: Scope[F] = new Scope[F](new Token(), None)

  /**
    * During scope being released multiple resources may fail their finalization.
    * This contains the first and subsequent failures that causes scope finalization to fail.
    */
  class ScopeReleaseFailure(
    first: Throwable
    , others: Catenable[Throwable]
  ) extends Throwable("Resources failed to finalize", first)

  /**
    * State of the scope
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
    *
    * @tparam F
    */
  final private[Scope] case class State[F[_]](
    open: Boolean
    , resources: Catenable[Resource[F]]
    , children: Catenable[Scope[F]]
  ) { self =>

    def unregisterResource(id: Token): (State[F], Option[Resource[F]]) = {
      self.resources.deleteFirst(_.id == id).fold((self, None: Option[Resource[F]])) { case (r, c) =>
        (self.copy(resources = c), Some(r))
      }
    }

    def unregisterChild(id: Token): (State[F], Option[Scope[F]]) = {
      self.children.deleteFirst(_.id == id).fold((self, None: Option[Scope[F]])) { case (s, c) =>
        (self.copy(children = c), Some(s))
      }
    }

    def close: State[F] = Scope.State.closed

  }


  private[Scope] object State {
    private val initial_ = State[Nothing](open = true, resources = Catenable.empty, children = Catenable.empty)
    def initial[F[_]]: State[F] = initial_.asInstanceOf[State[F]]

    private val closed_ = State[Nothing](open = false, resources = Catenable.empty, children = Catenable.empty)
    def closed[F[_]]: State[F] = closed_.asInstanceOf[State[F]]

  }



}
