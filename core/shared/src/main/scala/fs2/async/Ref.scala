package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Effect, IO }

import fs2.Scheduler
import fs2.async
//import fs2.internal.{Actor,LinkedMap}
import fs2.internal.LinkedMap
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import scala.annotation.tailrec

import Ref._

/** An asynchronous, concurrent mutable reference. */
final class Ref[F[_],A] private[fs2] (implicit F: Effect[F], ec: ExecutionContext) extends RefOps[F,A] { self =>

  private val state = new AtomicReference[St[A]]
  state.set(new St[A])
  //TODO in the old implementation Read and Nevermind submit the continuation
  // to the EC, whereas Set and TrySet call it directly, why?
  def read(id: MsgId, cb: ((A, Long)) => Unit): Unit = {
    @tailrec
    def spin(): Unit = {
      val st = state.get

      if (st.result eq null) {
        val newSt: St[A] = new St(st.result, st.waiting.updated(id, cb), st.nonce)
        if (!state.compareAndSet(st, newSt)) spin
      } else {
        ec.execute { () => cb(st.result.value -> st.nonce) }
      }
    }

    ec.execute { () => spin() }
  }
  
  def set(r: A, cb: () => Unit): Unit = {
    var notify: () => Unit = () => ()

    @tailrec
    def spin(): Unit = {
      val st = state.get
      notify = () => ()

      if(st.result eq null) {
        notify = () => st.waiting.values.foreach { cb =>
          ec.execute { () => cb(r -> st.nonce) }
        }
      }

      val newSt = new St(new Box(r), LinkedMap.empty, st.nonce + 1L )
      if(!state.compareAndSet(st, newSt)) spin()
    }

    ec.execute { () => spin(); notify(); cb() }
  }

  def trySet(id: Long, r: A, cb: Boolean => Unit): Unit = ec.execute { () =>
    var notify: () => Unit = () => ()

    val st = state.get
    if (st.result eq null) {
        notify = () => st.waiting.values.foreach { cb =>
          ec.execute { () => cb(r -> st.nonce) }
        }
    }
    val newSt = new St(new Box(r), LinkedMap.empty, st.nonce + 1L)

    if(id == st.nonce && state.compareAndSet(st, newSt)) {
      notify()
      cb(true)
    } else {
      cb(false)
    }
  }

  def nevermind(id: MsgId, cb: () => Unit): Unit = {
    @tailrec
    def spin(): Unit = {
      val st = state.get
      val newSt = new St(st.result, st.waiting - id, st.nonce)
      if(!state.compareAndSet(st, newSt)) spin()
    }
    ec.execute{ () => spin(); cb() }
  }

  // private var result: Box[A] = null
  // // any waiting calls to `access` before first `set`
  // private var waiting: LinkedMap[MsgId, ((A, Long)) => Unit] = LinkedMap.empty
  // // id which increases with each `set` or successful `modify`
  // private var nonce: Long = 0

  // private val actor: Actor[Msg[A]] = Actor[Msg[A]] {
  //   case Msg.Read(cb, idf) =>
  //     if (result eq null) {
  //       waiting = waiting.updated(idf, cb)
  //     } else {
  //       val r = result.value
  //       val id = nonce
  //       ec.execute { () => cb(r -> id) }
  //     }

  //   case Msg.Set(r, cb) =>
  //     nonce += 1L
  //     if (result eq null) {
  //       val id = nonce
  //       waiting.values.foreach { cb =>
  //         ec.execute { () => cb(r -> id) }
  //       }
  //       waiting = LinkedMap.empty
  //     }
  //     result = new Box(r)
  //     cb()

  //   case Msg.TrySet(id, r, cb) =>
  //     if (id == nonce) {
  //       nonce += 1L
  //       if (result eq null) {
  //         val id2 = nonce
  //         waiting.values.foreach { cb =>
  //           ec.execute { () => cb(r -> id2) }
  //         }
  //         waiting = LinkedMap.empty
  //       }
  //       result = new Box(r)
  //       cb(true)
  //     }
  //     else cb(false)

  //   case Msg.Nevermind(id, cb) =>
  //     waiting = waiting - id
  //     ec.execute { () => cb() }
  // }

  /**
   * Obtains a snapshot of the current value of the `Ref`, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access: F[(A, A => F[Boolean])] =
    F.flatMap(F.delay(new MsgId)) { mid =>
      F.map(getStamped(mid)) { case (a, id) =>
        val set = (a: A) =>
          F.async[Boolean] { cb => trySet(id, a, x => cb(Right(x))) }
        (a, set)
      }
    }

  override def get: F[A] = F.flatMap(F.delay(new MsgId)) { mid => F.map(getStamped(mid))(_._1) }

  /** Like [[get]] but returns an `F[Unit]` that can be used cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] = F.delay {
    val id = new MsgId
    val get = F.map(getStamped(id))(_._1)
    val cancel = F.async[Unit] {
      cb => nevermind(id, () => cb(Right(())))
    }
    (get, cancel)
  }

  /**
   * Like [[get]] but if the ref has not been initialized when the timeout is reached, a `None`
   * is returned.
   */
  def timedGet(timeout: FiniteDuration, scheduler: Scheduler): F[Option[A]] =
    cancellableGet.flatMap { case (g, cancelGet) =>
      scheduler.effect.delayCancellable(F.unit, timeout).flatMap { case (timer, cancelTimer) =>
        fs2.async.race(g, timer).flatMap(_.fold(a => cancelTimer.as(Some(a)), _ => cancelGet.as(None)))
      }
    }

  override def tryModify(f: A => A): F[Option[Change[A]]] =
    access.flatMap { case (previous,set) =>
      val now = f(previous)
      set(now).map { b =>
        if (b) Some(Change(previous, now))
        else None
      }
    }

  override def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] =
    access.flatMap { case (previous,set) =>
      val (now,b0) = f(previous)
      set(now).map { b =>
        if (b) Some(Change(previous, now) -> b0)
        else None
      }
  }

  override def modify(f: A => A): F[Change[A]] =
    tryModify(f).flatMap {
      case None => modify(f)
      case Some(change) => F.pure(change)
    }

  override def modify2[B](f: A => (A,B)): F[(Change[A], B)] =
    tryModify2(f).flatMap {
      case None => modify2(f)
      case Some(changeAndB) => F.pure(changeAndB)
    }

  // might want to eliminate this, since one can just fork at call site
  override def setAsyncPure(a: A): F[Unit] =
    async.fork {
      F.async[Unit] { cb => set(a, () => cb(Right(()))) }
    }  //actor ! Msg.Set(a, () => ()) }

  // not sure why the shift here
  override def setSyncPure(a: A): F[Unit] =
    F.async[Unit](cb => set(a, () => cb(Right(())))) *> F.shift

  /**
   * Runs `f1` and `f2` simultaneously, but only the winner gets to
   * `set` to this `ref`. The loser continues running but its reference
   * to this ref is severed, allowing this ref to be garbage collected
   * if it is no longer referenced by anyone other than the loser.
   *
   * If the winner fails, the returned `F` fails as well, and this `ref`
   * is not set.
   */
  def race(f1: F[A], f2: F[A]): F[Unit] = F.delay {
    val ref = new AtomicReference(this)
    val won = new AtomicBoolean(false)
    val win = (res: Either[Throwable,A]) => {
      // important for GC: we don't reference this ref
      // or the actor directly, and the winner destroys any
      // references behind it!
      if (won.compareAndSet(false, true)) {
        val current = ref.get
        ref.set(null)

        res match {
          case Left(e) => throw e
          case Right(v) =>  current.set(v, () => ())//actor ! Msg.Set(v, () => ())
        }
      }
    }
    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }

  private def getStamped(msg: MsgId): F[(A,Long)] =
    F.async[(A,Long)] { cb => read(msg, x => cb(Right(x))) } //actor ! Msg.Read(x => cb(Right(x)), msg) }
}

object Ref {

  /** Creates an asynchronous, concurrent mutable reference. */
  def uninitialized[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Ref[F,A]] =
    F.delay(new Ref[F, A])

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def initialized[F[_]: Effect, A](a: A)(implicit ec: ExecutionContext): F[Ref[F,A]] =
    uninitialized[F, A].flatMap(r => r.setSyncPure(a).as(r))

  private final class MsgId
  private final class St[A](val result: Box[A] = null, val waiting: LinkedMap[MsgId, ((A, Long)) => Unit] = LinkedMap.empty[MsgId, ((A, Long)) => Unit], val nonce: Long = 0)
  private final class Box[A](val value: A)
  // private sealed abstract class Msg[A]
  // private object Msg {
  //   final case class Read[A](cb: ((A, Long)) => Unit, id: MsgId) extends Msg[A]
  //   final case class Nevermind[A](id: MsgId, cb: () => Unit) extends Msg[A]
  //   final case class Set[A](r: A, cb: () => Unit) extends Msg[A]
  //   final case class TrySet[A](id: Long, r: A, cb: Boolean => Unit) extends Msg[A]
  // }
}
