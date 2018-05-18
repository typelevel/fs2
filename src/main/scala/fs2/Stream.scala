package fs2

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
    else ??? //fromFold(Algebra.output[Pure, O](Segment.seq(os)))

  private[fs2] val empty_ =
    fromFold[Nothing, Nothing](Fold.pure[Nothing, Nothing, Unit](())): Stream[Nothing, Nothing]

  /** Empty pure stream. */
  def empty: Stream[Pure, Nothing] = empty_

  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromFold(Fold.output1Eval(fo))

}
