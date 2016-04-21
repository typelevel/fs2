package fs2

import fs2.util.{RealSupertype,Sub1}

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * that are implemented in terms of `Pipe2`.
 */
private[fs2] trait StreamPipe2Ops[+F[_],+O] { self: Stream[F,O] =>

  /** Alias for `(this through2v s2)(pipe2.either)`. */
  def either[F2[_]:Async,O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2]): Stream[F2,Either[O,O2]] =
    (self through2v s2)(pipe2.either)

  /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
  def interruptWhen[F2[_]](haltWhenTrue: Stream[F2,Boolean])(implicit S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O] =
    (haltWhenTrue through2 Sub1.substStream(self))(pipe2.interrupt)

  /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
  def interruptWhen[F2[_]](haltWhenTrue: async.immutable.Signal[F2,Boolean])(implicit S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O] =
    (haltWhenTrue.discrete through2 Sub1.substStream(self))(pipe2.interrupt)

  def interleave[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.interleave)

  def interleaveAll[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.interleaveAll)

  def merge[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.merge)

  def mergeHaltBoth[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltBoth)

  def mergeHaltL[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltL)

  def mergeHaltR[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltR)

  def mergeDrainL[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeDrainL)

  def mergeDrainR[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Async[F2]): Stream[F2,O] =
    (self through2v s2)(pipe2.mergeDrainR)

  def zip[F2[_],O2](s2: Stream[F2,O2])(implicit S:Sub1[F,F2]): Stream[F2,(O,O2)] =
    (self through2v s2)(pipe2.zip)

  def zipWith[F2[_],O2,O3](s2: Stream[F2,O2])(f: (O,O2) => O3)(implicit S:Sub1[F,F2]): Stream[F2, O3] =
    (self through2v s2)(pipe2.zipWith(f))
}
