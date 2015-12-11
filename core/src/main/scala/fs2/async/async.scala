package fs2

import fs2.util.Catchable


/**
 * Created by pach on 10/10/15.
 */
package object async {

  /**
   * Creates a new continuous signal which may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[F[_]:AsyncExt,A](initialValue: A): F[mutable.Signal[F,A]] =
    mutable.Signal(initialValue)

  /** Defined as `[[hold]](None, source.map(Some(_)))` */
  def holdOption[F[_]:AsyncExt,A](source: Stream[F, A]): Stream[F, immutable.Signal[F,Option[A]]] =
     immutable.Signal.holdOption(source)

  /**
   * Converts a discrete stream to a signal. Returns a single-element stream.
   *
   * Resulting signal is initially `initial`, and is updated with latest value
   * produced by `source`. If `source` is empty, the resulting signal will always
   * be `initial`.
   *
   * @param source   discrete process publishing values to this signal
   */
  def hold[F[_]:AsyncExt,A](initial: A, source: Stream[F, A]): Stream[F, immutable.Signal[F,A]] =
     immutable.Signal.hold(initial, source)
}
