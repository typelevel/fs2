package fs2

trait Process1Ops[+F[_],+O] { self: Stream[F,O] =>

  // note: these are in alphabetical order

  /** Alias for `self pipe [[process1.chunkLimit]]`. */
  def chunkLimit(n: Int): Stream[F,Chunk[O]] = self pipe process1.chunkLimit(n)

  /** Alias for `self pipe [[process1.chunkN]]`. */
  def chunkN(n: Int, allowFewer: Boolean = true): Stream[F,List[Chunk[O]]] =
    self pipe process1.chunkN(n, allowFewer)

  /** Alias for `self pipe [[process1.chunks]]`. */
  def chunks: Stream[F,Chunk[O]] = self pipe process1.chunks

  /** Alias for `self pipe [[process1.collect]]`. */
  def collect[O2](pf: PartialFunction[O, O2]) = self pipe process1.collect(pf)

  /** Alias for `self pipe [[process1.collectFirst]]`. */
  def collectFirst[O2](pf: PartialFunction[O, O2]) = self pipe process1.collectFirst(pf)

  /** Alias for `self pipe [[process1.delete]]`. */
  def delete(f: O => Boolean): Stream[F,O] = self pipe process1.delete(f)

  /** Alias for `self pipe [[process1.drop]]`. */
  def drop(n: Int): Stream[F,O] = self pipe process1.drop(n)

  /** Alias for `self pipe [[process1.dropWhile]]` */
  def dropWhile(p: O => Boolean): Stream[F,O] = self pipe process1.dropWhile(p)

  /** Alias for `self pipe [[process1.exists]]`. */
  def exists(f: O => Boolean): Stream[F, Boolean] = self pipe process1.exists(f)

  /** Alias for `self pipe [[process1.filter]]`. */
  def filter(f: O => Boolean): Stream[F,O] = self pipe process1.filter(f)

  /** Alias for `self pipe [[process1.find]]`. */
  def find(f: O => Boolean): Stream[F,O] = self pipe process1.find(f)

  /** Alias for `self pipe [[process1.fold]](z)(f)`. */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self pipe process1.fold(z)(f)

  /** Alias for `self pipe [[process1.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self pipe process1.fold1(f)

  /** Alias for `self pipe [[process1.forall]]`. */
  def forall(f: O => Boolean): Stream[F, Boolean] = self pipe process1.forall(f)

  /** Alias for `self pipe [[process1.last]]`. */
  def last: Stream[F,Option[O]] = self pipe process1.last

  /** Alias for `self pipe [[process1.lastOr]]`. */
  def lastOr[O2 >: O](li: => O2): Stream[F,O2] = self pipe process1.lastOr(li)

  /** Alias for `self pipe [[process1.mapChunks]](f)`. */
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] = self pipe process1.mapChunks(f)

  /** Alias for `self pipe [[process1.mapAccumulate]]` */
  def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] =
    self pipe process1.mapAccumulate(init)(f)

  /** Alias for `self pipe [[process1.reduce]](z)(f)`. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self pipe process1.reduce(f)

  /** Alias for `self pipe [[process1.scan]](z)(f)`. */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self pipe process1.scan(z)(f)

  /** Alias for `self pipe [[process1.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self pipe process1.scan1(f)

  /** Alias for `self pipe [[process1.shiftRight]]`. */
  def shiftRight[O2 >: O](head: O2*): Stream[F,O2] = self pipe process1.shiftRight(head: _*)

  /** Alias for `self pipe [[process1.sum]](f)`. */
  def sum[O2 >: O : Numeric]: Stream[F,O2] = self pipe process1.sum

  /** Alias for `self pipe [[process1.tail]]`. */
  def tail: Stream[F,O] = self pipe process1.tail

  /** Alias for `self pipe [[process1.take]](n)`. */
  def take(n: Long): Stream[F,O] = self pipe process1.take(n)

  /** Alias for `self pipe [[process1.takeRight]]`. */
  def takeRight(n: Long): Stream[F,O] = self pipe process1.takeRight(n)

  /** Alias for `self pipe [[process1.takeThrough]]`. */
  def takeThrough(p: O => Boolean): Stream[F,O] = self pipe process1.takeThrough(p)

  /** Alias for `self pipe [[process1.takeWhile]]`. */
  def takeWhile(p: O => Boolean): Stream[F,O] = self pipe process1.takeWhile(p)

  /** Alias for `self pipe [[process1.unchunk]]`. */
  def unchunk: Stream[F,O] = self pipe process1.unchunk

  /** Alias for `self pipe [[process1.vectorChunkN]]`. */
  def vectorChunkN(n: Int, allowFewer: Boolean = true): Stream[F,Vector[O]] =
    self pipe process1.vectorChunkN(n, allowFewer)

  /** Alias for `self pipe [[process1.zipWithIndex]]`. */
  def zipWithIndex: Stream[F, (O, Int)] = self pipe process1.zipWithIndex

  /** Alias for `self pipe [[process1.zipWithNext]]`. */
  def zipWithNext: Stream[F, (O, Option[O])] = self pipe process1.zipWithNext

  /** Alias for `self pipe [[process1.zipWithPrevious]]`. */
  def zipWithPrevious: Stream[F, (Option[O], O)] = self pipe process1.zipWithPrevious

  /** Alias for `self pipe [[process1.zipWithPreviousAndNext]]`. */
  def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] = self pipe process1.zipWithPreviousAndNext
}
