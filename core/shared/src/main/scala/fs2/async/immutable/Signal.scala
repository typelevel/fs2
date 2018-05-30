package fs2.async.immutable

import cats.data.OptionT

import scala.concurrent.ExecutionContext
import cats.{Applicative, Functor}
import cats.effect.Effect
import cats.implicits._
import fs2.{Pull, Stream}

/** Data type of a single value of type `A` that can be read in the effect `F`. */
abstract class Signal[F[_], A] { self =>

  /**
    * Returns the discrete version of this signal, updated only when the value
    * is changed.
    *
    * The value _may_ change several times between reads, but it is
    * guaranteed the latest value will be emitted after a series of changes.
    *
    * If you want to be notified about every single change, use `async.queue` for signalling.
    */
  def discrete: Stream[F, A]

  /**
    * Returns the continuous version of this signal, which emits the
    * current `A` value on each request for an element from the stream.
    *
    * Note that this may not see all changes of `A` as it
    * always gets the current `A` at each request for an element.
    */
  def continuous: Stream[F, A]

  /**
    * Asynchronously gets the current value of this `Signal`.
    */
  def get: F[A]
}

object Signal extends SignalLowPriorityImplicits {

  implicit def applicativeInstance[F[_]](
      implicit effectEv: Effect[F],
      ec: ExecutionContext
  ): Applicative[Signal[F, ?]] =
    new Applicative[Signal[F, ?]] {
      override def map[A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] = Signal.map(fa)(f)

      override def pure[A](x: A): Signal[F, A] = fs2.async.mutable.Signal.constant(x)

      override def ap[A, B](ff: Signal[F, A => B])(fa: Signal[F, A]): Signal[F, B] =
        new Signal[F, B] {

          override def discrete: Stream[F, B] =
            nondeterministicZip(ff.discrete, fa.discrete).map { case (f, a) => f(a) }

          override def continuous: Stream[F, B] = Stream.repeatEval(get)

          override def get: F[B] = ff.get.ap(fa.get)
        }
    }

  private def nondeterministicZip[F[_], A0, A1](xs: Stream[F, A0], ys: Stream[F, A1])(
      implicit effectEv: Effect[F],
      ec: ExecutionContext
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

  private[immutable] def map[F[_]: Functor, A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] =
    new Signal[F, B] {
      def continuous: Stream[F, B] = fa.continuous.map(f)
      def discrete: Stream[F, B] = fa.discrete.map(f)
      def get: F[B] = Functor[F].map(fa.get)(f)
    }

  implicit class ImmutableSignalSyntax[F[_], A](val self: Signal[F, A]) {

    /**
      * Converts this signal to signal of `B` by applying `f`.
      */
    def map[B](f: A => B)(implicit F: Functor[F]): Signal[F, B] =
      Signal.map(self)(f)
  }

  implicit class BooleanSignalSyntax[F[_]](val self: Signal[F, Boolean]) {
    def interrupt[A](s: Stream[F, A])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, A] =
      s.interruptWhen(self)
  }
}

trait SignalLowPriorityImplicits {

  /**
    * Note that this is not subsumed by [[Signal.applicativeInstance]] because
    * [[Signal.applicativeInstance]] requires an [[ExecutionContext]] and
    * [[Effect]] to work with since it non-deterministically zips elements
    * together while our [[Functor]] instance has no other constraints.
    *
    * Separating the two instances allows us to make the [[Functor]] instance
    * more general.
    *
    * We put this in a [[SignalLowPriorityImplicits]] to resolve ambiguous
    * implicits if the [[Signal.applicativeInstance]] is applicable, allowing
    * the [[Applicative]]'s [[Functor]] instance to win.
    */
  implicit def functorInstance[F[_]: Functor]: Functor[Signal[F, ?]] =
    new Functor[Signal[F, ?]] {
      override def map[A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] = Signal.map(fa)(f)
    }
}
