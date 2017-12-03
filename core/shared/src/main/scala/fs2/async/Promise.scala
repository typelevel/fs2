package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.{Effect, IO}

import fs2.Scheduler
import fs2.internal.{LinkedMap, Token}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import Promise._

/**
 * A purely functional synchronisation primitive.
 *
 * When created, a `Promise` is unset. It can then be set exactly once, and never be unset again.
 *
 * `get` on an unset `Promise` will block until the `Promise` is set.
 * `get` on a set `Promise` will always immediately return its content.
 *
 * `set(a)` on an unset `Promise` will set it to `a`, and notify any and all readers currently blocked on a call to `get`.
 * `set(a)` on a set `Promise` will not modify its content, and result in a failed `F`.
 *
 * Albeit simple, `Promise` can be used in conjunction with [[Ref]] to build complex concurrent behaviour and
 * data structures like queues and semaphores. Finally, the blocking mentioned above is semantic only, no
 * actual threads are blocked by the implementation.
 */
final class Promise[F[_], A] private[fs2] (ref: Ref[F, State[A]])(implicit F: Effect[F], ec: ExecutionContext) {

  /** Obtains the value of the `Promise`, or waits until it has been set. */
  def get: F[A] = F.suspend {
    // `new Token` is a side effect because `Token`s are compared with reference equality, it needs to be in `F`.
    // For performance reasons, `suspend` is preferred to `F.delay(...).flatMap` here.
    val id = new Token
    getOrWait(id)
  }

  /** Like [[get]] but returns an `F[Unit]` that can be used to cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] = F.delay {
    // see the comment on `get` about Token
    val id = new Token
    val get = getOrWait(id)
    val cancel = ref.modify {
      case s @ State.Set(_) => s
      case State.Unset(waiting) => State.Unset(waiting - id)
    }.void

    (get, cancel)
  }

  /**
   * Like [[get]] but if the `Promise` has not been initialized when the timeout is reached, a `None`
   * is returned.
   */
  def timedGet(timeout: FiniteDuration, scheduler: Scheduler): F[Option[A]] =
    cancellableGet.flatMap { case (g, cancelGet) =>
      scheduler.effect.delayCancellable(F.unit, timeout).flatMap { case (timer, cancelTimer) =>
        fs2.async.race(g, timer).flatMap(_.fold(a => cancelTimer.as(Some(a)), _ => cancelGet.as(None)))
      }
    }

  /**
   * If this `Promise` is unset, *synchronously* sets the current value to `a`, and notifies
   * any and all readers currently blocked on a `get`.
   *
   * Note that the returned action completes after the reference has been successfully set:
   * use `async.fork(r.setSync)` if you want asynchronous behaviour.
   *
   * If this `Promise` is already set, the returned action immediately fails with a [[Ref.AlreadySetException]].
   * In the uncommon scenario where this behaviour is problematic, you can handle failure explicitly
   * using `attempt` or any other `ApplicativeError`/`MonadError` combinator on the returned action.
   *
   * Satisfies:
   *   `Promise.empty[F, A].flatMap(r => r.setSync(a) *> r.get) == a`
   */
  def setSync(a: A): F[Unit] = {
    def notifyReaders(r: State.Unset[A]): Unit =
      r.waiting.values.foreach { cb =>
        ec.execute { () => cb(a) }
      }

    ref.modify2 {
      case s @ State.Set(_) => s -> F.raiseError[Unit](new AlreadySetException)
      case u @ State.Unset(_) => State.Set(a) -> F.delay(notifyReaders(u))
    }.flatMap(_._2)
  }

  /**
   * Runs `f1` and `f2` simultaneously, but only the winner gets to
   * set this `Promise`. The loser continues running but its reference
   * to this `Promise` is severed, allowing this `Promise` to be garbage collected
   * if it is no longer referenced by anyone other than the loser.
   *
   * If the winner fails, the returned `F` fails as well, and this `Promise`
   * is not set.
   */
  def race(f1: F[A], f2: F[A])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] = F.delay {
    val refToSelf = new AtomicReference(this)
    val won = new AtomicBoolean(false)
    val win = (res: Either[Throwable,A]) => {
      // important for GC: we don't reference this ref directly, and the
      // winner destroys any references behind it!
      if (won.compareAndSet(false, true)) {
        res match {
          case Left(e) =>
            refToSelf.set(null)
            throw e
          case Right(v) =>
            val action = refToSelf.getAndSet(null).setSync(v)
            unsafeRunAsync(action)(_ => IO.unit)
        }
      }
    }

    unsafeRunAsync(f1)(res => IO(win(res)))
    unsafeRunAsync(f2)(res => IO(win(res)))
  }

  private def getOrWait(id: Token): F[A] = {
    def registerCallBack(cb: A => Unit): Unit = {
      def go = ref.modify2 {
        case s @ State.Set(a) => s -> F.delay(cb(a))
        case State.Unset(waiting) => State.Unset(waiting.updated(id, cb)) -> F.unit
      }.flatMap(_._2)

      unsafeRunAsync(go)(_ => IO.unit)
    }

    ref.get.flatMap {
      case State.Set(a) => F.pure(a)
      case State.Unset(_) => F.async(cb => registerCallBack(x => cb(Right(x))))
      }
  }
}
object Promise {
  /** Creates a concurrent synchronisation primitive, currently unset **/
  def empty[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Promise[F, A]] =
    F.delay(unsafeCreate[F, A])

  /** Raised when trying to set a `Promise` that's already been set once */
  final class AlreadySetException extends Throwable(
    s"Trying to set an fs2 Promise that's already been set"
  )

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Token, A => Unit]) extends State[A]
  }

  private[fs2] def unsafeCreate[F[_]: Effect, A](implicit ec: ExecutionContext): Promise[F, A] =
    new Promise[F, A](new Ref(new AtomicReference(Promise.State.Unset(LinkedMap.empty))))

}
