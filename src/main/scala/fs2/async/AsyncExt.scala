package fs2.async

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import fs2.async.AsyncExt.Change
import fs2.internal.Actor
import fs2.util.Task.{Result, Callback}
import fs2.{Strategy, Async}
import fs2.util.{Task, Free}

import scala.collection.immutable.Queue


/**
 * Created by pach on 24/10/15.
 */
trait AsyncExt[F[_]] extends Async[F] {

  /**
   * Modifies `ref` by applying pure function `f` to create computation `F` that's value
   * will be used to modify the `ref`, once computed.
   *
   * During evaluation of `F` all other modifications will be queued up,
   * but the `get` will receive the `A` that was supplied to `f`.
   *
   * If the `ref` is not yet set, this queues up until `ref` is set for the first time.
   *
   * Note that order of queued modifications is not preserved
   *
   * Likewise with `set` Resulting task may complete before final value is applied to `ref`,
   * but it is guaranteed that it will be applied before any other modify/set on the `ref`.
   *
   */
  def modify[A](ref:Ref[A])(f: A => F[A]):F[Change[A]]

  /**
   * Like `modify` but allows to interrupt the modify task (Interruptee)
   * in case the task is waiting for the `ref` to be set for the first time by evaluating the
   * second returned task (Interruptor)
   *
   * Interruptor is guaranteed to complete always with true, if Interruptee was interrupted before
   * evaluation started, and false if Interuptee was completed before interruption.
   *
   * Note that Interruptor won't be able to interrupt computation after `f` was evaluated and produced computation.
   */
  def cancellableModify[A](r:Ref[A])(f:A => F[A]):F[(F[Change[A]], F[Boolean])]


}


object AsyncExt {

  /**
   * Result of Ref modification. When the Ref Was modified succesfully,
   * `previous` contains value before modification took place and
   * `next` contains value _after_ modification was applied.
   */
  case class Change[+A](previous:A, now:A)



  private trait MsgId
  private trait Msg[A]
  private object Msg {
    case class Get[A](cb: Callback[A], id: () => MsgId) extends Msg[A]
    case class Nevermind[A](id: MsgId, cb:Callback[Boolean]) extends Msg[A]
    case class Set[A](r: Result[A]) extends Msg[A]
    case class Modify[A](cb:Callback[A], id: () => MsgId) extends Msg[A]
    case class ModifySet[A](r:Result[A]) extends Msg[A]
  }


  private type RefState[A] = Either[Queue[(MsgId,A => Task[A])],A]

  def refExt[A](implicit S: Strategy): Task[RefExt[A]] = Task.delay {
    var result:Result[A] = null
    var modified:Boolean = false //when true, the ref is just modified, and any sets/modify must be queued
    var waiting: Map[MsgId,Callback[A]] = Map.empty  // any waiting gets before first set
    var waitingModify:Map[MsgId,Callback[A]] = Map.empty // any sets/modify during ref is modified.

    def deqModify(r:Result[A]):Unit = {
      waitingModify.headOption match {
        case None => modified = false
        case Some((id,cb)) =>
          modified = true
          waitingModify = waitingModify - id
          S { cb(r) }
      }
    }

    def delayedSet(sr:Result[A])(r:Result[A]):Unit =
      r.right.foreach { _ => S { actor ! Msg.Set(sr) } }


    lazy val actor:Actor[Msg[A]] = Actor.actor[Msg[A]] {
      case Msg.Get(cb,idf) =>
        if (result eq null) waiting = waiting + (idf() -> cb)
        else { val r = result; S { cb(r) } }

      case Msg.Set(r) =>
        if (modified) waitingModify + (new MsgId{} -> delayedSet(r) _)
        else {
          if (result eq null) {
            waiting.values.foreach(cb => S { cb(r) })
            waiting = Map.empty
            deqModify(r)
          }
          result = r
        }

      case mod@Msg.Modify(cb,idf) =>
        if (modified || (result eq null)) waitingModify + (idf() -> cb)
        else {
         modified = true
         val r = result
         S { cb(r) }
        }

      case Msg.ModifySet(r) =>
        result = r
        deqModify(r)

      case Msg.Nevermind(id,cb) =>
        val interrupted = waiting.isDefinedAt(id) || waitingModify.isDefinedAt(id)
        waiting = waiting - id
        waitingModify = waiting -id
        S { cb (Right(interrupted)) }

    }


    new RefExt(actor)
  }


  class RefExt[A](actor: Actor[Msg[A]]) {
    /**
     * Return a `Task` that submits `t` to this ref for evaluation.
     * When it completes it overwrites any previously `put` value.
     */
    def set(t: Task[A]): Task[Unit] = Task.delay { t.runAsync { r => actor ! Msg.Set(r) } }
    def setFree(t: Free[Task,A]): Task[Unit] = set(t.run)

    /** Return the most recently completed `set`, or block until a `set` value is available. */
    def get: Task[A] = Task.async { cb => actor ! Msg.Get(cb, () => new MsgId {} ) }

    /** Like `get`, but returns a `Task[Unit]` that can be used cancel the subscription. */
    def cancellableGet: Task[(Task[A], Task[Unit])] = Task.delay {
      lazy val id = new MsgId {}
      val get = Task.async[A] { cb => actor ! Msg.Get(cb, () => id ) }
      val cancel = Task.async[Unit] { cb => actor ! Msg.Nevermind(id, r => cb(r.right.map(_ => ()))) }
      (get, cancel)
    }

    /**
     * Runs `t1` and `t2` simultaneously, but only the winner gets to
     * `set` to this `ref`. The loser continues running but its reference
     * to this ref is severed, allowing this ref to be garbage collected
     * if it is no longer referenced by anyone other than the loser.
     */
    def setRace(t1: Task[A], t2: Task[A]): Task[Unit] = Task.delay {
      val ref = new AtomicReference(actor)
      val won = new AtomicBoolean(false)
      val win = (res: Either[Throwable,A]) => {
        // important for GC: we don't reference this ref
        // or the actor directly, and the winner destroys any
        // references behind it!
        if (won.compareAndSet(false, true)) {
          val actor = ref.get
          ref.set(null)
          actor ! Msg.Set(res)
        }
      }
      t1.runAsync(win)
      t2.runAsync(win)
    }

    def modify(f:A => Task[A]):Task[Change[A]] = {
      for {
        a <- Task.async[A] { cb => actor ! Msg.Modify(cb,() => new MsgId {})}
        r <- f(a).attempt
        _ <- Task.delay { actor ! Msg.ModifySet(r) }
        aa <- r.fold(Task.fail,na => Task.now(Change(a,na)))
      } yield aa
    }

    def cancellableModify(f:A => Task[A]):Task[(Task[Change[A]], Task[Boolean])]  = Task.delay {
      lazy val id = new MsgId {}
      val mod = modify(f)
      val cancel = Task.async[Boolean] { cb => actor ! Msg.Nevermind(id, cb) }
      (mod,cancel)
    }


  }





  implicit def taskInstance(implicit S:Strategy): AsyncExt[Task] = new AsyncExt[Task] {
    type Ref[A] = RefExt[A]
    def set[A](q: Ref[A])(a: Task[A]): Task[Unit] = q.set(a)
    def ref[A]: Task[Ref[A]] = refExt[A](S)
    def get[A](r: Ref[A]): Task[A] = r.get
    def cancellableGet[A](r: Ref[A]): Task[(Task[A], Task[Unit])] = r.cancellableGet
    def setFree[A](q: Ref[A])(a: Free[Task, A]): Task[Unit] = q.setFree(a)
    def bind[A, B](a: Task[A])(f: (A) => Task[B]): Task[B] = a flatMap f
    def pure[A](a: A): Task[A] = Task.now(a)
    def modify[A](ref: Ref[A])(f: (A) => Task[A]): Task[Change[A]] = ref.modify(f)
    def cancellableModify[A](r: Ref[A])(f: (A) => Task[A]): Task[(Task[Change[A]], Task[Boolean])] = r.cancellableModify(f)
    def fail[A](err: Throwable): Task[A] = Task.fail(err)
    def attempt[A](fa: Task[A]): Task[Either[Throwable, A]] = fa.attempt
  }


}
