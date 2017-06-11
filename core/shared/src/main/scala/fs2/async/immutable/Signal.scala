package fs2.async.immutable

import scala.concurrent.ExecutionContext

import cats.Functor
import cats.effect.Effect

import fs2.Stream
import fs2.async.immutable

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

object Signal {

  implicit class ImmutableSignalSyntax[F[_], A] (val self: Signal[F, A])  {

    /**
     * Converts this signal to signal of `B` by applying `f`.
     */
    def map[B](f: A => B)(implicit F: Functor[F]): Signal[F,B] = new Signal[F, B] {
      def continuous: Stream[F, B] = self.continuous.map(f)
      def discrete: Stream[F, B] = self.discrete.map(f)
      def get: F[B] = F.map(self.get)(f)
    }
  }

  implicit class BooleanSignalSyntax[F[_]] (val self: Signal[F,Boolean]) {
    def interrupt[A](s: Stream[F,A])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,A] = s.interruptWhen(self)
  }

  /**
   * Constructs Stream from the input stream `source`. If `source` terminates
   * then resulting stream terminates as well.
   */
  def holdOption[F[_],A](source:Stream[F,A])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,immutable.Signal[F,Option[A]]] =
    hold(None, source.map(Some(_)))

  def hold[F[_],A](initial: A, source:Stream[F,A])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,immutable.Signal[F,A]] =
    Stream.eval(fs2.async.signalOf[F,A](initial)) flatMap { sig =>
      Stream(sig).merge(source.flatMap(a => Stream.eval_(sig.set(a))))
    }
}
