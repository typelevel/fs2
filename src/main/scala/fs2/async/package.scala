package fs2


/**
 * Created by pach on 10/10/15.
 */
package object async {


  /**
   * Create a new continuous signal which may be controlled asynchronously.
   * Note that this would block any resulting processes (discrete, continuous) until any signal value is set.
   */
  def signalUnset[F[_]:Async,A]: F[mutable.Signal[F,A]] =
    ???

  /**
   * Creates a new continuous signal which may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[F[_]:Async,A](initialValue: A): F[mutable.Signal[F,A]] =
    mutable.Signal(initialValue)


  /**
   * Converts discrete process to signal.
   *
   * Resulting signal is set to None, if the source did not produce any value and then
   * Contains last value produced by `A`.
   *
   * @param source          discrete process publishing values to this signal
   */
  def toSignal[F[_]:Async,A](source: fs2.Stream[F, A]): F[immutable.Signal[F,Option[A]]] =
     immutable.Signal.fromStream(source)

  
}
