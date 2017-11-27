package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Effect, IO }

import fs2.Scheduler
import fs2.internal.{Actor,LinkedMap}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import Ref._

/** An asynchronous, concurrent mutable reference. */
final class Ref[F[_],A] private[fs2] (implicit F: Effect[F], ec: ExecutionContext) extends RefOps[F,A] { self =>

  private var result: Box[A] = null
  // any waiting calls to `access` before first `set`
  private var waiting: LinkedMap[MsgId, ((A, Long)) => Unit] = LinkedMap.empty
  // id which increases with each `set` or successful `modify`
  private var nonce: Long = 0

  private val actor: Actor[Msg[A]] = Actor[Msg[A]] {
    case Msg.Read(cb, idf) =>
      if (result eq null) {
        waiting = waiting.updated(idf, cb)
      } else {
        val r = result.value
        val id = nonce
        ec.execute { () => cb(r -> id) }
      }

    case Msg.Set(r, cb) =>
      nonce += 1L
      if (result eq null) {
        val id = nonce
        waiting.values.foreach { cb =>
          ec.execute { () => cb(r -> id) }
        }
        waiting = LinkedMap.empty
      }
      result = new Box(r)
      cb()

    case Msg.TrySet(id, r, cb) =>
      if (id == nonce) {
        nonce += 1L; val id2 = nonce
        waiting.values.foreach { cb =>
          ec.execute { () => cb(r -> id2) }
        }
        waiting = LinkedMap.empty
        result = new Box(r)
        cb(true)
      }
      else cb(false)

    case Msg.Nevermind(id, cb) =>
      waiting = waiting - id
      ec.execute { () => cb() }
  }

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
          F.async[Boolean] { cb => actor ! Msg.TrySet(id, a, x => cb(Right(x))) }
        (a, set)
      }
    }

  override def get: F[A] = F.flatMap(F.delay(new MsgId)) { mid => F.map(getStamped(mid))(_._1) }

  /** Like [[get]] but returns an `F[Unit]` that can be used cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] = F.delay {
    val id = new MsgId
    val get = F.map(getStamped(id))(_._1)
    val cancel = F.async[Unit] {
      cb => actor ! Msg.Nevermind(id, () => cb(Right(())))
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

  override def setAsyncPure(a: A): F[Unit] =
    F.delay { actor ! Msg.Set(a, () => ()) }

  override def setSyncPure(a: A): F[Unit] =
    F.async[Unit](cb => actor ! Msg.Set(a, () => cb(Right(())))) *> F.shift

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
    val ref = new AtomicReference(actor)
    val won = new AtomicBoolean(false)
    val win = (res: Either[Throwable,A]) => {
      // important for GC: we don't reference this ref
      // or the actor directly, and the winner destroys any
      // references behind it!
      if (won.compareAndSet(false, true)) {
        val actor = ref.get
        ref.set(null)

        res match {
          case Left(e) => throw e
          case Right(v) => actor ! Msg.Set(v, () => ())
        }
      }
    }
    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }

  private def getStamped(msg: MsgId): F[(A,Long)] =
    F.async[(A,Long)] { cb => actor ! Msg.Read(x => cb(Right(x)), msg) }
}

object Ref {

  /** Creates an asynchronous, concurrent mutable reference. */
  def uninitialized[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Ref[F,A]] =
    F.delay(new Ref[F, A])

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def initialized[F[_]: Effect, A](a: A)(implicit ec: ExecutionContext): F[Ref[F,A]] =
    uninitialized[F, A].flatMap(r => r.setSyncPure(a).as(r))

  private final class MsgId
  private final class Box[A](val value: A)
  private sealed abstract class Msg[A]
  private object Msg {
    final case class Read[A](cb: ((A, Long)) => Unit, id: MsgId) extends Msg[A]
    final case class Nevermind[A](id: MsgId, cb: () => Unit) extends Msg[A]
    final case class Set[A](r: A, cb: () => Unit) extends Msg[A]
    final case class TrySet[A](id: Long, r: A, cb: Boolean => Unit) extends Msg[A]
  }
}
