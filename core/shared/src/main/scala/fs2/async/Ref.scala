package fs2.async

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Effect, IO }

import fs2.Scheduler
import fs2.internal.LinkedMap
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import Ref._

final class Ref[F[_], A] private[fs2] (implicit F: Effect[F], ec: ExecutionContext) extends RefOps[F, A] { self =>

  private[this] val state: AtomicReference[State[A]] =
    new AtomicReference(Waiting(LinkedMap.empty))

  /**
   * Obtains a snapshot of the current value of the `Ref`, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access: F[(A, A => F[Boolean])] = {
    getDone.map { d =>
      val setter = { a: A =>
        F.delay { state.compareAndSet(d, Done(a)) }
      }
      (d.a, setter)
    }
  }

  override def get: F[A] =
    F.delay(new MsgId).flatMap(get)

  /** Like [[get]] but returns an `F[Unit]` that can be used cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] = F.delay {
    val id = new MsgId
    val cancel = F.delay { removeCallback(id) }
    (get(id), cancel)
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
    getDone.flatMap { ov => F.delay(singleCas(ov, f(ov.a))) }

  override def tryModify2[B](f: A => (A, B)): F[Option[(Change[A], B)]] = {
    getDone.flatMap { ov =>
      val (nv, b) = f(ov.a)
      F.delay(singleCas(ov, nv)).map(_.map(ch => (ch, b)))
    }
  }

  override def modify(f: A => A): F[Change[A]] =
    getDone.flatMap { ov => F.delay(casLoop(ov, f)) }

  override def modify2[B](f: A => (A, B)): F[(Change[A], B)] =
    getDone.flatMap { ov => F.delay(casLoop2(ov, f)) }

  override def setAsyncPure(a: A): F[Unit] =
    setSyncPure(a).as(()) // FIXME

  override def setSyncPure(a: A): F[Unit] = F.delay {
    val r = Done(a)
    state.getAndSet(r) match {
      case Waiting(cbs) =>
        // need to notify waiters
        // FIXME: fork?
        cbs.values.foreach { cb => cb(r) }
      case Done(_) =>
        ()
    }
  }

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
    val win = (res: Either[Throwable, A]) => {
      // Important for GC: we don't reference this ref directly,
      // and the winner destroys any references behind it!
      if (won.compareAndSet(false, true)) {
        res match {
          case Left(e) =>
            ref.set(null)
            throw e
          case Right(a) =>
            val self = ref.getAndSet(null)
            unsafeRunAsync(self.setSyncPure(a))(_ => IO.unit)
        }
      }
    }
    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }

  private def get(id: MsgId): F[A] =
    getDone(id).map(_.a)

  private def getDone: F[Done[A]] =
    F.delay(new MsgId).flatMap(getDone)

  private def getDone(id: MsgId): F[Done[A]] = F.suspend {
    val s = state.get()
    s match {
      case Waiting(cbs) =>
        F.async[Done[A]] { cb =>
          insertCallback(s, id, done => cb(Right(done)))
        }
      case d @ Done(_) =>
        F.pure(d)
    }
  }

  @tailrec
  private def insertCallback(curr: State[A], id: MsgId, cb: Done[A] => Unit): Unit = {
    curr match {
      case ov @ Waiting(cbs) =>
        val nv = Waiting[A](cbs.updated(id, cb))
        if (!state.compareAndSet(ov, nv)) {
          insertCallback(state.get(), id, cb)
        }
      case r @ Done(_) =>
        cb(r)
    }
  }

  @tailrec
  private def removeCallback(id: MsgId): Unit = {
    val s = state.get()
    s match {
      case ov @ Waiting(cbs) =>
        val nv = Waiting(cbs - id)
        if (!state.compareAndSet(ov, nv)) {
          removeCallback(id)
        }
      case Done(_) =>
        ()
    }
  }

  private def singleCas(ov: Done[A], nv: A): Option[Change[A]] = {
    if (state.compareAndSet(ov, Done(nv))) {
      Some(Change(previous = ov.a, now = nv))
    } else {
      None
    }
  }

  @tailrec
  private def casLoop(curr: Done[A], f: A => A): Change[A] = {
    val a = curr.a
    val now = f(a)
    if (state.compareAndSet(curr, Done(now))) {
      Change(previous = a, now = now)
    } else {
      casLoop(state.get().unsafeAsDone(), f)
    }
  }

  @tailrec
  private def casLoop2[B](curr: Done[A], f: A => (A, B)): (Change[A], B) = {
    val a = curr.a
    val (now, b) = f(a)
    if (state.compareAndSet(curr, Done(now))) {
      (Change(previous = a, now = now), b)
    } else {
      casLoop2(state.get().unsafeAsDone(), f)
    }
  }
}

final object Ref {

  /** Creates an asynchronous, concurrent mutable reference. */
  def uninitialized[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Ref[F,A]] =
    F.delay(new Ref[F, A])

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def initialized[F[_]: Effect, A](a: A)(implicit ec: ExecutionContext): F[Ref[F,A]] =
    uninitialized[F, A].flatMap(r => r.setSyncPure(a).as(r))

  private final class MsgId

  private sealed abstract class State[A] {
    def unsafeAsDone(): Done[A]
  }

  private final case class Waiting[A](cbs: LinkedMap[MsgId, Done[A] => Unit]) extends State[A] {
    override def unsafeAsDone() = throw new IllegalStateException("Waiting.unsafeAsDone()")
  }

  private final case class Done[A](a: A) extends State[A] {
    override def unsafeAsDone() = this
  }
}
