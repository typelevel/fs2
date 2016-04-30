package fs2.async.immutable

import fs2.{pipe, Async, Stream}
import fs2.util.Functor

import fs2.Async
import fs2.async.immutable


/** A holder of a single value of type `A` that can be read in the effect `F`. */
trait Signal[F[_],A]  {

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
  def discrete: Stream[F, A]

  /**
   * Returns the continuous version of this signal, always equal to the
   * current `A` inside `value`.
   *
   * Note that this may not see all changes of `A` as it
   * gets always current fresh `A` at every request.
   */
  def continuous: Stream[F, A]

  /**
   * Returns the discrete version of `changed`. Will emit `Unit`
   * when the `value` is changed.
   */
  def changes: Stream[F, Unit]

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
      def continuous: Stream[F, B] = self.continuous.map(f)
      def changes: Stream[F, Unit] = self.discrete.through(pipe.changes(_ == _)).map(_ => ())
      def discrete: Stream[F, B] = self.discrete.map(f)
      def get: F[B] = implicitly[Functor[F]].map(self.get)(f)
    }
  }

  implicit class BooleanSignalSyntax[F[_]:Async] (val self: Signal[F,Boolean]) {
    def interrupt[A](s: Stream[F,A]): Stream[F,A] = s.interruptWhen(self)
  }

  /**
   * Constructs Stream from the input stream `source`. If `source` terminates
   * then resulting stream terminates as well.
   */
  def holdOption[F[_],A](source:Stream[F,A])(implicit F: Async[F]): Stream[F,immutable.Signal[F,Option[A]]] =
    hold(None, source.map(Some(_)))

  def hold[F[_],A](initial: A, source:Stream[F,A])(implicit F: Async[F]): Stream[F,immutable.Signal[F,A]] =
    Stream.eval(fs2.async.signalOf[F,A](initial)) flatMap { sig =>
      Stream(sig).merge(source.flatMap(a => Stream.eval_(sig.set(a))))
    }
}
