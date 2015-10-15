package fs2

trait Process1Ops[+F[_],+O] { self: Stream[F,O] =>

  // note: these are in alphabetical order

  /** Alias for `self pipe [[process1.chunks]]`. */
  def chunks: Stream[F,Chunk[O]] = self pipe process1.chunks

  /** Alias for `self pipe [[process1.chunks]]`. */
  def last: Stream[F,Option[O]] = self pipe process1.last

  /** Alias for `self pipe [[process1.take]](n)`. */
  def take(n: Int): Stream[F,O] = self pipe process1.take(n)

  /** Alias for `self pipe [[process1.unchunk]]`. */
  def unchunk: Stream[F,O] = self pipe process1.unchunk
}
