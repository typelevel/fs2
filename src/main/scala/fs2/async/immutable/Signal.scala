package fs2.async.immutable

import fs2.{Async, process1}
import fs2.util.Functor

import fs2.async.{immutable, mutable}


/**
 * Created by pach on 10/10/15.
 */
trait Signal[F[_],A]  {
  /**
   * Returns a continuous stream, indicating whether the value has changed.
   * This will spike `true` once for each time the value of `Signal` was changed.
   * It will always start with `true` when the process is run or when the `Signal` is
   * set for the first time.
   */
  def changed: fs2.Stream[F, Boolean]

  /**
   * Returns the discrete version stream of this signal, updated only when `value`
   * is changed.
   *
   * Value _may_ change several times between reads, but it is
   * guaranteed this will always get latest known value after any change.
   *
   * If you want to be notified about every single change use `async.queue` for signalling.
   *
   * It will emit the current value of the Signal after being run or when the signal
   * is set for the first time
   */
  def discrete: fs2.Stream[F, A]

  /**
   * Returns the continuous version of this signal, always equal to the
   * current `A` inside `value`.
   *
   * Note that this may not see all changes of `A` as it
   * gets always current fresh `A` at every request.
   */
  def continuous: fs2.Stream[F, A]

  /**
   * Returns the discrete version of `changed`. Will emit `Unit`
   * when the `value` is changed.
   */
  def changes: fs2.Stream[F, Boolean]


  /**
   * Asynchronously get the current value of this `Signal`
   */
  def get: F[A]


}


object Signal {

  implicit class ImmutableSignalSyntax[F[_] : Async,A] (val self: Signal[F,A])  {
    /**
     * Converts this signal to signal of `B` by applying `f`
     */
    def map[B](f: A => B):Signal[F,B] = new Signal[F,B] {
      def changed: fs2.Stream[F, Boolean] = self.changed
      def continuous: fs2.Stream[F, B] = self.continuous.map(f)
      def changes: fs2.Stream[F, Boolean] = self.changes
      def discrete: fs2.Stream[F, B] = self.discrete.map(f)
      def get: F[B] = implicitly[Functor[F]].map(self.get)(f)
    }

    /**
     * Converts this signal to signal of `B` by applying transducer `process1`
     * to any new value of signal of `A`
     *
     * Note that, if the transducer filters any `A` received to not producing any `B`
     * then resulting signal won't update any `changed` or `changes` streams.
     *
     * Also note that before any value from `p1` is emitted, the resulting signal is set to None
     */
    def pipe[B](p1: process1.Process1[A,B]):F[Signal[F,Option[B]]] =
      fromStream(self.discrete.pipe(p1))

  }


  def fromStream[F[_]:Async,A](stream:fs2.Stream[F,A]):F[immutable.Signal[F,Option[A]]] = {
    ???
  }


}
