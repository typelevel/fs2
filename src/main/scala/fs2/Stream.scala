package fs2

import cats._
import cats.effect._
import cats.implicits._

final class Stream[+F[_], +O] private (private val _fold: Fold[Nothing, Nothing, Unit])
    extends AnyVal {

  private[fs2] def fold[F2[x] >: F[x], O2 >: O]: Fold[F2, O2, Unit] =
    _fold.asInstanceOf[Fold[F2, O2, Unit]]

}

object Stream {
  private[fs2] def fromFold[F[_], O](fold: Fold[F, O, Unit]): Stream[F, O] =
    new Stream(fold.asInstanceOf[Fold[Nothing, Nothing, Unit]])

  def apply[O](os: O*): Stream[Pure, O] = emits(os)

  def emit[O](o: O): Stream[Pure, O] = fromFold(Fold.output1[Pure, O](o))

  def emits[O](os: Seq[O]): Stream[Pure, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromFold(Fold.output[Pure, O](Segment.seq(os)))

  private[fs2] val empty_ =
    fromFold[Nothing, Nothing](Fold.pure[Nothing, Nothing, Unit](())): Stream[Nothing, Nothing]

  /** Empty pure stream. */
  def empty: Stream[Pure, Nothing] = empty_

  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromFold(Fold.eval(fo).flatMap(o => Fold.output1(o)))

  /**
    * Creates a pure stream that emits the values of the supplied segment.
    *
    * @example {{{
    * scala> Stream.segment(Segment.from(0)).take(5).toList
    * res0: List[Long] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def segment[O](s: Segment[O, Unit]): Stream[Pure, O] =
    fromFold(Fold.output[Pure, O](s))

  /**
    * Lazily produce the range `[start, stopExclusive)`. If you want to produce
    * the sequence in one chunk, instead of lazily, use
    * `emits(start until stopExclusive)`.
    *
    * @example {{{
    * scala> Stream.range(10, 20, 2).toList
    * res0: List[Int] = List(10, 12, 14, 16, 18)
    * }}}
    */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Stream[Pure, Int] =
    unfold(start) { i =>
      if ((by > 0 && i < stopExclusive && start < stopExclusive) ||
          (by < 0 && i > stopExclusive && start > stopExclusive))
        Some((i, i + by))
      else None
    }

  /**
    * Creates a stream by successively applying `f` until a `None` is returned, emitting
    * each output `O` and using each output `S` as input to the next invocation of `f`.
    *
    * @example {{{
    * scala> Stream.unfold(0)(i => if (i < 5) Some(i -> (i+1)) else None).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def unfold[S, O](s: S)(f: S => Option[(O, S)]): Stream[Pure, O] =
    segment(Segment.unfold(s)(f))

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  implicit def InvariantOps[F[_], O](s: Stream[F, O]): InvariantOps[F, O] =
    new InvariantOps(s.fold)

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  final class InvariantOps[F[_], O] private[Stream] (private val fold: Fold[F, O, Unit])
      extends AnyVal {
    private def self: Stream[F, O] = Stream.fromFold(fold)

    /** Appends `s2` to the end of this stream. */
    def ++[O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] = self.append(s2)

    /** Appends `s2` to the end of this stream. Alias for `s1 ++ s2`. */
    def append[O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      fromFold(self.fold >> s2.fold)

    /**
      * Gets a projection of this stream that allows converting it to an `F[..]` in a number of ways.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> val prg: IO[Vector[Int]] = Stream.eval(IO(1)).append(Stream(2,3,4)).compile.toVector
      * scala> prg.unsafeRunSync
      * res2: Vector[Int] = Vector(1, 2, 3, 4)
      * }}}
      */
    def compile: Stream.ToEffect[F, O] = new Stream.ToEffect[F, O](self._fold)

    def flatMap[O2](f: O => Stream[F, O2]): Stream[F, O2] =
      Stream.fromFold[F, O2](self.fold[F, O].unfold.flatMap {
        case Right((hd, tl)) =>
          hd.map(f)
            .foldRightLazy(Stream.fromFold(tl).flatMap(f))(_ ++ _)
            .fold
        case Left(()) =>
          Stream.empty.covaryAll[F, O2].fold
      })
  }

  /** Provides syntax for pure empty pipes. */
  implicit def EmptyOps(s: Stream[Pure, Nothing]): EmptyOps =
    new EmptyOps(s.fold[Pure, Nothing])

  /** Provides syntax for pure empty pipes. */
  final class EmptyOps private[Stream] (private val fold: Fold[Pure, Nothing, Unit])
      extends AnyVal {
    private def self: Stream[Pure, Nothing] =
      Stream.fromFold[Pure, Nothing](fold)

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F, Nothing] = self.asInstanceOf[Stream[F, Nothing]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F[_], O]: Stream[F, O] = self.asInstanceOf[Stream[F, O]]
  }

  /** Provides syntax for pure pipes. */
  implicit def PureOps[O](s: Stream[Pure, O]): PureOps[O] =
    new PureOps(s.fold[Pure, O])

  /** Provides syntax for pure pipes. */
  final class PureOps[O] private[Stream] (private val fold: Fold[Pure, O, Unit]) extends AnyVal {
    private def self: Stream[Pure, O] = Stream.fromFold[Pure, O](fold)

    // def ++[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
    //   covary[F].append(s2)

    // def append[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
    //   covary[F].append(s2)

    // def concurrently[F[_], O2](that: Stream[F, O2])(implicit F: Concurrent[F],
    //                                                 ec: ExecutionContext): Stream[F, O] =
    //   covary[F].concurrently(that)

    def covary[F[_]]: Stream[F, O] = self.asInstanceOf[Stream[F, O]]

    def covaryAll[F[_], O2 >: O]: Stream[F, O2] =
      self.asInstanceOf[Stream[F, O2]]

    // def debounce[F[_]](d: FiniteDuration)(implicit F: Concurrent[F],
    //                                       ec: ExecutionContext,
    //                                       timer: Timer[F]): Stream[F, O] = covary[F].debounce(d)

    // def delayBy[F[_]](d: FiniteDuration)(implicit F: Timer[F]): Stream[F, O] = covary[F].delayBy(d)

    // def either[F[_], O2](s2: Stream[F, O2])(implicit F: Concurrent[F],
    //                                         ec: ExecutionContext): Stream[F, Either[O, O2]] =
    //   covary[F].either(s2)

    // def evalMap[F[_], O2](f: O => F[O2]): Stream[F, O2] = covary[F].evalMap(f)

    // def evalScan[F[_], O2](z: O2)(f: (O2, O) => F[O2]): Stream[F, O2] =
    //   covary[F].evalScan(z)(f)

    // def mapAsync[F[_], O2](parallelism: Int)(f: O => F[O2])(
    //     implicit F: Concurrent[F],
    //     executionContext: ExecutionContext): Stream[F, O2] =
    //   covary[F].mapAsync(parallelism)(f)

    // def mapAsyncUnordered[F[_], O2](parallelism: Int)(f: O => F[O2])(
    //     implicit F: Concurrent[F],
    //     executionContext: ExecutionContext): Stream[F, O2] =
    //   covary[F].mapAsyncUnordered(parallelism)(f)

    // def flatMap[F[_], O2](f: O => Stream[F, O2]): Stream[F, O2] =
    //   covary[F].flatMap(f)

    // def >>[F[_], O2](s2: => Stream[F, O2]): Stream[F, O2] = flatMap { _ =>
    //   s2
    // }

    // def interleave[F[_], O2 >: O](s2: Stream[F, O2]): Stream[F, O2] =
    //   covaryAll[F, O2].interleave(s2)

    // def interleaveAll[F[_], O2 >: O](s2: Stream[F, O2]): Stream[F, O2] =
    //   covaryAll[F, O2].interleaveAll(s2)

    // def interruptWhen[F[_]](haltWhenTrue: Stream[F, Boolean])(implicit F: Concurrent[F],
    //                                                           ec: ExecutionContext): Stream[F, O] =
    //   covary[F].interruptWhen(haltWhenTrue)

    // def interruptWhen[F[_]](haltWhenTrue: async.immutable.Signal[F, Boolean])(
    //     implicit F: Concurrent[F],
    //     ec: ExecutionContext): Stream[F, O] =
    //   covary[F].interruptWhen(haltWhenTrue)

    // def join[F[_], O2](maxOpen: Int)(implicit ev: O <:< Stream[F, O2],
    //                                  F: Concurrent[F],
    //                                  ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].join(maxOpen)

    // def joinUnbounded[F[_], O2](implicit ev: O <:< Stream[F, O2],
    //                             F: Concurrent[F],
    //                             ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].joinUnbounded

    // def merge[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Concurrent[F],
    //                                               ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].merge(that)

    // def mergeHaltBoth[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Concurrent[F],
    //                                                       ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].mergeHaltBoth(that)

    // def mergeHaltL[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Concurrent[F],
    //                                                    ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].mergeHaltL(that)

    // def mergeHaltR[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Concurrent[F],
    //                                                    ec: ExecutionContext): Stream[F, O2] =
    //   covary[F].mergeHaltR(that)

    // def observe1[F[_]](f: O => F[Unit])(implicit F: Functor[F]): Stream[F, O] =
    //   covary[F].observe1(f)

    // def observe[F[_]](sink: Sink[F, O])(implicit F: Concurrent[F],
    //                                     ec: ExecutionContext): Stream[F, O] =
    //   covary[F].observe(sink)

    // def observeAsync[F[_]](maxQueued: Int)(sink: Sink[F, O])(implicit F: Concurrent[F],
    //                                                          ec: ExecutionContext): Stream[F, O] =
    //   covary[F].observeAsync(maxQueued)(sink)

    // def onComplete[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
    //   covary[F].onComplete(s2)

    // def handleErrorWith[F[_], O2 >: O](h: Throwable => Stream[F, O2]): Stream[F, O2] =
    //   covary[F].handleErrorWith(h)

    // def onFinalize[F[_]](f: F[Unit])(implicit F: Applicative[F]): Stream[F, O] =
    //   covary[F].onFinalize(f)

    // def pauseWhen[F[_]](pauseWhenTrue: Stream[F, Boolean])(implicit F: Concurrent[F],
    //                                                        ec: ExecutionContext): Stream[F, O] =
    //   covary[F].pauseWhen(pauseWhenTrue)

    // def pauseWhen[F[_]](pauseWhenTrue: async.immutable.Signal[F, Boolean])(
    //     implicit F: Concurrent[F],
    //     ec: ExecutionContext): Stream[F, O] =
    //   covary[F].pauseWhen(pauseWhenTrue)

    /** Runs this pure stream and returns the emitted elements in a list. Note: this method is only available on pure streams. */
    def toList: List[O] = covary[IO].compile.toList.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a vector. Note: this method is only available on pure streams. */
    def toVector: Vector[O] = covary[IO].compile.toVector.unsafeRunSync

    // def zipAll[F[_], O2](that: Stream[F, O2])(pad1: O, pad2: O2): Stream[F, (O, O2)] =
    //   covary[F].zipAll(that)(pad1, pad2)

    // def zipAllWith[F[_], O2, O3](that: Stream[F, O2])(pad1: O, pad2: O2)(
    //     f: (O, O2) => O3): Stream[F, O3] =
    //   covary[F].zipAllWith(that)(pad1, pad2)(f)

    // def zip[F[_], O2](s2: Stream[F, O2]): Stream[F, (O, O2)] =
    //   covary[F].zip(s2)

    // def zipWith[F[_], O2, O3](s2: Stream[F, O2])(f: (O, O2) => O3): Stream[F, O3] =
    //   covary[F].zipWith(s2)(f)
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class ToEffect[F[_], O] private[Stream] (private val _self: Fold[Nothing, Nothing, Unit])
      extends AnyVal {

    import scala.collection.generic.CanBuildFrom

    private def self: Stream[F, O] =
      Stream.fromFold(_self.asInstanceOf[Fold[F, O, Unit]])

    /**
      * Compiles this stream in to a value of the target effect type `F` and
      * discards any output values of the stream.
      *
      * To access the output values of the stream, use one of the other compilation methods --
      * e.g., [[fold]], [[toVector]], etc.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def drain(implicit F: Sync[F]): F[Unit] = fold(())((u, o) => u)

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output values together, starting with the provided `init` and combining the
      * current value with each output value.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def fold[B](init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
      self.fold.fold(init)(f).map(_._1)

    /**
      * Like [[fold]] but uses the implicitly available `Monoid[O]` to combine elements.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldMonoid.unsafeRunSync
      * res0: Int = 15
      * }}}
      */
    def foldMonoid(implicit F: Sync[F], O: Monoid[O]): F[O] =
      fold(O.empty)(O.combine)

    /**
      * Like [[fold]] but uses the implicitly available `Semigroup[O]` to combine elements.
      * If the stream emits no elements, `None` is returned.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldSemigroup.unsafeRunSync
      * res0: Option[Int] = Some(15)
      * scala> Stream.empty.covaryAll[IO,Int].compile.foldSemigroup.unsafeRunSync
      * res1: Option[Int] = None
      * }}}
      */
    def foldSemigroup(implicit F: Sync[F], O: Semigroup[O]): F[Option[O]] =
      fold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * returning `None` if the stream emitted no values and returning the
      * last value emitted wrapped in `Some` if values were emitted.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.last.unsafeRunSync
      * res0: Option[Int] = Some(4)
      * }}}
      */
    def last(implicit F: Sync[F]): F[Option[O]] =
      fold(Option.empty[O])((_, a) => Some(a))

    /**
      * Compiles this stream into a value of the target effect type `F` by logging
      * the output values to a `C`, given a `CanBuildFrom`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.to[List].unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def to[C[_]](implicit F: Sync[F], cbf: CanBuildFrom[Nothing, O, C[O]]): F[C[O]] =
      F.suspend(F.map(fold(cbf())(_ += _))(_.result))

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `List`. Equivalent to `to[List]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def toList(implicit F: Sync[F]): F[List[O]] =
      to[List]

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Vector`. Equivalent to `to[Vector]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
      * }}}
      */
    def toVector(implicit F: Sync[F]): F[Vector[O]] =
      to[Vector]

  }
}
