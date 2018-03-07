package fs2.async

import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.{Concurrent, IO}

import fs2.internal.{LinkedMap, Token}

import java.util.concurrent.atomic.AtomicReference

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
final class Promise[F[_], A] private[fs2] (ref: AtomicReference[State[A]])(
    implicit F: Concurrent[F]) {

  /** Obtains the value of the `Promise`, or waits until it has been completed. */
  def get: F[A] =
    F.delay(ref.get).flatMap {
      case State.Set(a) => F.pure(a)
      case State.Unset(_) =>
        F.cancelable { cb =>
          val id = new Token

          def register: Option[A] =
            ref.get match {
              case State.Set(a) => Some(a)
              case s @ State.Unset(waiting) =>
                val updated = State.Unset(waiting.updated(id, (a: A) => cb(Right(a))))
                if (ref.compareAndSet(s, updated)) None
                else register
            }

          register.foreach(a => cb(Right(a)))

          def unregister(): Unit =
            ref.get match {
              case State.Set(_) => ()
              case s @ State.Unset(waiting) =>
                val updated = State.Unset(waiting - id)
                if (ref.compareAndSet(s, updated)) ()
                else unregister
            }
          IO(unregister())
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
      r.waiting.values.foreach { cb =>
        cb(a)
      }

    F.delay(ref.get).flatMap {
      case s @ State.Set(_) => F.raiseError[Unit](new AlreadyCompletedException)
      case s @ State.Unset(_) =>
        if (ref.compareAndSet(s, State.Set(a))) {
          F.delay(notifyReaders(s))
        } else complete(a)
    }
  }
}

object Promise {

  /** Creates a concurrent synchronisation primitive, currently unset **/
  def empty[F[_], A](implicit F: Concurrent[F]): F[Promise[F, A]] =
    F.delay(unsafeCreate[F, A])

  /** Raised when trying to complete a [[Promise]] that's already been completed */
  final class AlreadyCompletedException
      extends Throwable(
        "Trying to complete an fs2 Promise that's already been completed"
      )

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Token, A => Unit]) extends State[A]
  }

  private[fs2] def unsafeCreate[F[_]: Concurrent, A]: Promise[F, A] =
    new Promise[F, A](new AtomicReference(Promise.State.Unset(LinkedMap.empty)))
}
