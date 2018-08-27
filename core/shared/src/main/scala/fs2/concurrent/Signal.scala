package fs2
package concurrent

import cats.{Applicative, Functor, Invariant}
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import fs2.internal.Token

/** Pure holder of a single value of type `A` that can be read in the effect `F`. */
trait ReadableSignal[F[_], A] {

  /**
    * Returns a stream of the updates to this signal.
    *
    * Updates that are very close together may result in only the last update appearing
    * in the stream. If you want to be notified about every single update, use
    * a `Queue` instead.
    */
  def discrete: Stream[F, A]

  /**
    * Returns a stream of the current value of the signal. An element is always
    * available -- on each pull, the current value is supplied.
    */
  def continuous: Stream[F, A]

  /**
    * Asynchronously gets the current value of this `ReadableSignal`.
    */
  def get: F[A]
}

object ReadableSignal extends ReadableSignalLowPriorityImplicits {

  def constant[F[_], A](a: A)(implicit F: Applicative[F]): ReadableSignal[F, A] =
    new ReadableSignal[F, A] {
      def get = F.pure(a)
      def continuous = Stream.constant(a)

      /**
        * We put a single element here because otherwise the implementations of
        * Signal as a Monad or Applicative get more annoying. In particular if
        * this stream were empty, Applicatively zipping another Signal in the
        * straightforward way would cause the (non-deterministically) zipped
        * stream to be empty.
        */
      def discrete = Stream(a)
    }

  implicit def applicativeInstance[F[_]](
      implicit F: Concurrent[F]
  ): Applicative[ReadableSignal[F, ?]] =
    new Applicative[ReadableSignal[F, ?]] {
      override def map[A, B](fa: ReadableSignal[F, A])(f: A => B): ReadableSignal[F, B] =
        ReadableSignal.map(fa)(f)

      override def pure[A](x: A): ReadableSignal[F, A] =
        Signal.constant(x)

      override def ap[A, B](ff: ReadableSignal[F, A => B])(
          fa: ReadableSignal[F, A]): ReadableSignal[F, B] =
        new ReadableSignal[F, B] {

          override def discrete: Stream[F, B] =
            nondeterministicZip(ff.discrete, fa.discrete).map { case (f, a) => f(a) }

          override def continuous: Stream[F, B] = Stream.repeatEval(get)

          override def get: F[B] = ff.get.ap(fa.get)
        }
    }

  private def nondeterministicZip[F[_], A0, A1](xs: Stream[F, A0], ys: Stream[F, A1])(
      implicit F: Concurrent[F]
  ): Stream[F, (A0, A1)] = {
    type PullOutput = (A0, A1, Stream[F, A0], Stream[F, A1])
    val firstPull = for {
      firstXAndRestOfXs <- OptionT(xs.pull.uncons1.covaryOutput[PullOutput])
      (x, restOfXs) = firstXAndRestOfXs
      firstYAndRestOfYs <- OptionT(ys.pull.uncons1.covaryOutput[PullOutput])
      (y, restOfYs) = firstYAndRestOfYs
      _ <- OptionT.liftF(Pull.output1[F, PullOutput]((x, y, restOfXs, restOfYs)))
    } yield ()
    firstPull.value.stream
      .covaryOutput[PullOutput]
      .flatMap {
        case (x, y, restOfXs, restOfYs) =>
          restOfXs.either(restOfYs).scan((x, y)) {
            case ((_, rightElem), Left(newElem)) => (newElem, rightElem)
            case ((leftElem, _), Right(newElem)) => (leftElem, newElem)
          }
      }
  }

  private[concurrent] def map[F[_]: Functor, A, B](fa: ReadableSignal[F, A])(
      f: A => B): ReadableSignal[F, B] =
    new ReadableSignal[F, B] {
      def continuous: Stream[F, B] = fa.continuous.map(f)
      def discrete: Stream[F, B] = fa.discrete.map(f)
      def get: F[B] = Functor[F].map(fa.get)(f)
    }

  implicit class ReadableSignalOps[F[_], A](val self: ReadableSignal[F, A]) extends AnyVal {

    /**
      * Converts this signal to signal of `B` by applying `f`.
      */
    def map[B](f: A => B)(implicit F: Functor[F]): ReadableSignal[F, B] =
      ReadableSignal.map(self)(f)
  }

  implicit class BooleanReadableSignalOps[F[_]](val self: ReadableSignal[F, Boolean])
      extends AnyVal {
    def interrupt[A](s: Stream[F, A])(implicit F: Concurrent[F]): Stream[F, A] =
      s.interruptWhen(self)
  }
}

private[concurrent] trait ReadableSignalLowPriorityImplicits {

  /**
    * Note that this is not subsumed by [[ReadableSignal.applicativeInstance]] because
    * [[ReadableSignal.applicativeInstance]] requires a `Concurrent[F]`
    * since it non-deterministically zips elements together while our
    * `Functor` instance has no other constraints.
    *
    * Separating the two instances allows us to make the [[Functor]] instance
    * more general.
    *
    * We put this in a [[ReadableSignalLowPriorityImplicits]] to resolve ambiguous
    * implicits if the [[ReadableSignal.applicativeInstance]] is applicable, allowing
    * the `Applicative` instance to be chosen.
    */
  implicit def functorInstance[F[_]: Functor]: Functor[ReadableSignal[F, ?]] =
    new Functor[ReadableSignal[F, ?]] {
      override def map[A, B](fa: ReadableSignal[F, A])(f: A => B): ReadableSignal[F, B] =
        ReadableSignal.map(fa)(f)
    }
}

/** Pure holder of a single value of type `A` that can be updated in the effect `F`. */
trait WritableSignal[F[_], A] {

  /** Sets the value of this signal. */
  def set(a: A): F[Unit]

  /**
    * Updates the current value using the supplied update function. If another modification
    * occurs between the time the current value is read and subsequently updated, the modification
    * is retried using the new value. Hence, `f` may be invoked multiple times.
    *
    * Satisfies:
    *   `r.modify(_ => a) == r.set(a)`
    */
  def update(f: A => A): F[Unit]

  /**
    * Like [[update]] but allows the update function to return an output value of type `B`
    */
  def modify[B](f: A => (A, B)): F[B]

  /**
    * Asynchronously refreshes the value of the signal,
    * keeping the value of this `Signal` the same, but notifing any listeners.
    */
  def refresh: F[Unit]
}

/** Pure holder of a single value of type `A` that can be both read and updated in the effect `F`. */
trait Signal[F[_], A] extends ReadableSignal[F, A] with WritableSignal[F, A]

object Signal {

  def constant[F[_], A](a: A)(implicit F: Applicative[F]): ReadableSignal[F, A] =
    ReadableSignal.constant(a)

  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): F[Signal[F, A]] =
    Ref
      .of[F, (A, Long, Map[Token, Deferred[F, (A, Long)]])]((initial, 0, Map.empty))
      .map { state =>
        new Signal[F, A] {
          def refresh: F[Unit] = update(identity)
          def set(a: A): F[Unit] = update(_ => a)
          def get: F[A] = state.get.map(_._1)
          def update(f: A => A): F[Unit] =
            modify(a => (f(a), ()))
          def modify[B](f: A => (A, B)): F[B] =
            state.modify {
              case (a, updates, listeners) =>
                val (newA, result) = f(a)
                val newUpdates = updates + 1
                val newState = (newA, newUpdates, Map.empty[Token, Deferred[F, (A, Long)]])
                val action = listeners.toVector.traverse {
                  case (_, deferred) =>
                    F.start(deferred.complete(newA -> newUpdates))
                }

                newState -> (action *> result.pure[F])
            }.flatten

          def continuous: Stream[F, A] =
            Stream.repeatEval(get)

          def discrete: Stream[F, A] = {
            def go(id: Token, lastUpdate: Long): Stream[F, A] = {
              def getNext: F[(A, Long)] =
                Deferred[F, (A, Long)]
                  .flatMap { deferred =>
                    state.modify {
                      case s @ (a, updates, listeners) =>
                        if (updates != lastUpdate) s -> (a -> updates).pure[F]
                        else (a, updates, listeners + (id -> deferred)) -> deferred.get
                    }.flatten
                  }

              Stream.eval(getNext).flatMap { case (a, l) => Stream.emit(a) ++ go(id, l) }
            }

            def cleanup(id: Token): F[Unit] =
              state.update(s => s.copy(_3 = s._3 - id))

            Stream.bracket(F.delay(new Token))(cleanup).flatMap { id =>
              Stream.eval(state.get).flatMap {
                case (a, l, _) => Stream.emit(a) ++ go(id, l)
              }
            }
          }
        }
      }

  implicit def invariantInstance[F[_]: Functor]: Invariant[Signal[F, ?]] =
    new Invariant[Signal[F, ?]] {
      override def imap[A, B](fa: Signal[F, A])(f: A => B)(g: B => A): Signal[F, B] =
        new Signal[F, B] {
          def discrete: Stream[F, B] = fa.discrete.map(f)
          def continuous: Stream[F, B] = fa.continuous.map(f)
          def get: F[B] = fa.get.map(f)
          def set(b: B): F[Unit] = fa.set(g(b))
          def refresh: F[Unit] = fa.refresh
          def update(bb: B => B): F[Unit] =
            modify(b => (bb(b), ()))
          def modify[B2](bb: B => (B, B2)): F[B2] =
            fa.modify { a =>
              val (a2, b2) = bb(f(a))
              g(a2) -> b2
            }
        }
    }
}
