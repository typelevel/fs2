package fs2.async

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.{Effect, IO}

import fs2.Scheduler
import fs2.internal.{LinkedMap, Token}

import java.util.concurrent.atomic.{AtomicReference}

import Promise._

/**
  * A purely functional synchronisation primitive.
  *
  * When created, a `Promise` is empty. It can then be completed exactly once, and never be made empty again.
  *
  * `get` on an empty `Promise` will block until the `Promise` is completed.
  * `get` on a completed `Promise` will always immediately return its content.
  *
  * `complete(a)` on an empty `Promise` will set it to `a`, and notify any and all readers currently blocked on a call to `get`.
  * `complete(a)` on a `Promise` that's already been completed will not modify its content, and result in a failed `F`.
  *
  * Albeit simple, `Promise` can be used in conjunction with [[Ref]] to build complex concurrent behaviour and
  * data structures like queues and semaphores.
  *
  * Finally, the blocking mentioned above is semantic only, no actual threads are blocked by the implementation.
  */
final class Promise[F[_], A] private[fs2] (ref: Ref[F, State[A]])(implicit F: Effect[F],
                                                                  ec: ExecutionContext) {

  /** Obtains the value of the `Promise`, or waits until it has been completed. */
  def get: F[A] = F.suspend {
    // `new Token` is a side effect because `Token`s are compared with reference equality, it needs to be in `F`.
    // For performance reasons, `suspend` is preferred to `F.delay(...).flatMap` here.
    val id = new Token
    getOrWait(id, true)
  }

  /** Like [[get]] but returns an `F[Unit]` that can be used to cancel the subscription. */
  def cancellableGet: F[(F[A], F[Unit])] =
    ref.get.flatMap {
      case State.Set(a) => F.pure((F.pure(a), F.unit))
      case State.Unset(_) =>
        val id = new Token
        val cancel = ref.modify {
          case s @ State.Set(_)     => s
          case State.Unset(waiting) => State.Unset(waiting - id)
        }.void
        ref
          .modify2 {
            case s @ State.Set(a) => s -> (F.pure(a) -> F.unit)
            case State.Unset(waiting) =>
              State.Unset(waiting.updated(id, Nil)) -> (getOrWait(id, false) -> cancel)
          }
          .map(_._2)
    }

  /**
    * Like [[get]] but if the `Promise` has not been completed when the timeout is reached, a `None`
    * is returned.
    */
  def timedGet(timeout: FiniteDuration, scheduler: Scheduler): F[Option[A]] =
    cancellableGet.flatMap {
      case (g, cancelGet) =>
        scheduler.effect.delayCancellable(F.unit, timeout).flatMap {
          case (timer, cancelTimer) =>
            fs2.async
              .race(g, timer)
              .flatMap(_.fold(a => cancelTimer.as(Some(a)), _ => cancelGet.as(None)))
        }
    }

  /**
    * If this `Promise` is empty, *synchronously* sets the current value to `a`, and notifies
    * any and all readers currently blocked on a `get`.
    *
    * Note that the returned action completes after the reference has been successfully set:
    * use `async.fork(r.complete)` if you want asynchronous behaviour.
    *
    * If this `Promise` has already been completed, the returned action immediately fails with a [[Promise.AlreadyCompletedException]].
    * In the uncommon scenario where this behaviour is problematic, you can handle failure explicitly
    * using `attempt` or any other `ApplicativeError`/`MonadError` combinator on the returned action.
    *
    * Satisfies:
    *   `Promise.empty[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
    */
  def complete(a: A): F[Unit] = {
    def notifyReaders(r: State.Unset[A]): Unit =
      r.waiting.values.foreach { cbs =>
        cbs.foreach { cb =>
          ec.execute { () =>
            cb(a)
          }
        }
      }

    ref
      .modify2 {
        case s @ State.Set(_) =>
          s -> F.raiseError[Unit](new AlreadyCompletedException)
        case u @ State.Unset(_) => State.Set(a) -> F.delay(notifyReaders(u))
      }
      .flatMap(_._2)
  }

  private def getOrWait(id: Token, forceRegistration: Boolean): F[A] = {
    def registerCallBack(cb: A => Unit): Unit = {
      def go =
        ref
          .modify2 {
            case s @ State.Set(a) => s -> F.delay(cb(a))
            case State.Unset(waiting) =>
              State.Unset(
                waiting
                  .get(id)
                  .map(cbs => waiting.updated(id, cb :: cbs))
                  .getOrElse(if (forceRegistration) waiting.updated(id, List(cb))
                  else waiting)
              ) -> F.unit
          }
          .flatMap(_._2)

      unsafeRunAsync(go)(_ => IO.unit)
    }
    ref.get.flatMap {
      case State.Set(a)   => F.pure(a)
      case State.Unset(_) => F.async(cb => registerCallBack(x => cb(Right(x))))
    }
  }
}

object Promise {

  /** Creates a concurrent synchronisation primitive, currently unset **/
  def empty[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Promise[F, A]] =
    F.delay(unsafeCreate[F, A])

  /** Raised when trying to complete a [[Promise]] that's already been completed */
  final class AlreadyCompletedException
      extends Throwable(
        "Trying to complete an fs2 Promise that's already been completed"
      )

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Token, List[A => Unit]]) extends State[A]
  }

  private[fs2] def unsafeCreate[F[_]: Effect, A](implicit ec: ExecutionContext): Promise[F, A] =
    new Promise[F, A](new Ref(new AtomicReference(Promise.State.Unset(LinkedMap.empty))))
}
