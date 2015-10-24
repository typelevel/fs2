package fs2

import fs2.util.Catchable


/**
 * Created by pach on 10/10/15.
 */
package object async {


  /**
   * Create a new continuous signal which may be controlled asynchronously.
   * Note that this would block any resulting processes (discrete, continuous) until any signal value is set.
   */
  def signalUnset[F[_]:AsyncExt,A]: Stream[F,mutable.Signal[F,A]] =
    ???

  /**
   * Creates a new continuous signal which may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[F[_]:AsyncExt : Catchable,A](initialValue: A): Stream[F,mutable.Signal[F,A]] =
    mutable.Signal(initialValue)


  /**
   * Converts discrete process to signal.
   *
   * Resulting signal is set to None, if the source did not produce any value and then
   * Contains last value produced by `A`.
   *
   * @param source          discrete process publishing values to this signal
   */
  def toSignal[F[_]:AsyncExt : Catchable,A](source: fs2.Stream[F, A]): Stream[F,immutable.Signal[F,Option[A]]] =
     immutable.Signal.fromStream(source)

  
}
