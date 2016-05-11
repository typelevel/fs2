package fs2

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * that are implemented in terms of `Pipe`.
 */
private[fs2] trait StreamPipeOps[+F[_],+O] { self: Stream[F,O] =>

  // note: these are in alphabetical order

  /** Alias for `self through [[pipe.chunkLimit]]`. */
  def chunkLimit(n: Int): Stream[F,Chunk[O]] = self through pipe.chunkLimit(n)

  /** Alias for `self through [[pipe.chunkN]]`. */
  def chunkN(n: Int, allowFewer: Boolean = true): Stream[F,List[Chunk[O]]] =
    self through pipe.chunkN(n, allowFewer)

  /** Alias for `self through [[pipe.chunks]]`. */
  def chunks: Stream[F,Chunk[O]] = self through pipe.chunks

  /** Alias for `self through [[pipe.collect]]`. */
  def collect[O2](pf: PartialFunction[O, O2]) = self through pipe.collect(pf)

  /** Alias for `self through [[pipe.collectFirst]]`. */
  def collectFirst[O2](pf: PartialFunction[O, O2]) = self through pipe.collectFirst(pf)

  /** Alias for `self through [[pipe.delete]]`. */
  def delete(f: O => Boolean): Stream[F,O] = self through pipe.delete(f)

  /** Alias for `self through [[pipe.drop]]`. */
  def drop(n: Int): Stream[F,O] = self through pipe.drop(n)

  /** Alias for `self through [[pipe.dropWhile]]` */
  def dropWhile(p: O => Boolean): Stream[F,O] = self through pipe.dropWhile(p)

  /** Alias for `self through [[pipe.exists]]`. */
  def exists(f: O => Boolean): Stream[F, Boolean] = self through pipe.exists(f)

  /** Alias for `self through [[pipe.filter]]`. */
  def filter(f: O => Boolean): Stream[F,O] = self through pipe.filter(f)

  /** Alias for `self through [[pipe.find]]`. */
  def find(f: O => Boolean): Stream[F,O] = self through pipe.find(f)

  /** Alias for `self through [[pipe.fold]](z)(f)`. */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.fold(z)(f)

  /** Alias for `self through [[pipe.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.fold1(f)

  /** Alias for `self through [[pipe.forall]]`. */
  def forall(f: O => Boolean): Stream[F, Boolean] = self through pipe.forall(f)

  /** Alias for `self through [[pipe.last]]`. */
  def last: Stream[F,Option[O]] = self through pipe.last

  /** Alias for `self through [[pipe.lastOr]]`. */
  def lastOr[O2 >: O](li: => O2): Stream[F,O2] = self through pipe.lastOr(li)

  /** Alias for `self through [[pipe.mapChunks]](f)`. */
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] = self through pipe.mapChunks(f)

  /** Alias for `self through [[pipe.mapAccumulate]]` */
  def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] =
    self through pipe.mapAccumulate(init)(f)

  /** Alias for `self through [[pipe.reduce]](z)(f)`. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.reduce(f)

  /** Alias for `self through [[pipe.scan]](z)(f)`. */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.scan(z)(f)

  /** Alias for `self through [[pipe.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.scan1(f)

  /** Alias for `self through [[pipe.shiftRight]]`. */
  def shiftRight[O2 >: O](head: O2*): Stream[F,O2] = self through pipe.shiftRight(head: _*)

  /** Alias for `self through [[pipe.sum]](f)`. */
  def sum[O2 >: O : Numeric]: Stream[F,O2] = self through pipe.sum

  /** Alias for `self through [[pipe.tail]]`. */
  def tail: Stream[F,O] = self through pipe.tail

  /** Alias for `self through [[pipe.take]](n)`. */
  def take(n: Long): Stream[F,O] = self through pipe.take(n)

  /** Alias for `self through [[pipe.takeRight]]`. */
  def takeRight(n: Long): Stream[F,O] = self through pipe.takeRight(n)

  /** Alias for `self through [[pipe.takeThrough]]`. */
  def takeThrough(p: O => Boolean): Stream[F,O] = self through pipe.takeThrough(p)

  /** Alias for `self through [[pipe.takeWhile]]`. */
  def takeWhile(p: O => Boolean): Stream[F,O] = self through pipe.takeWhile(p)

  /** Alias for `self through [[pipe.unchunk]]`. */
  def unchunk: Stream[F,O] = self through pipe.unchunk

  /** Alias for `self through [[pipe.vectorChunkN]]`. */
  def vectorChunkN(n: Int, allowFewer: Boolean = true): Stream[F,Vector[O]] =
    self through pipe.vectorChunkN(n, allowFewer)

  /** Alias for `self through [[pipe.zipWithIndex]]`. */
  def zipWithIndex: Stream[F, (O, Int)] = self through pipe.zipWithIndex

  /** Alias for `self through [[pipe.zipWithNext]]`. */
  def zipWithNext: Stream[F, (O, Option[O])] = self through pipe.zipWithNext

  /** Alias for `self through [[pipe.zipWithPrevious]]`. */
  def zipWithPrevious: Stream[F, (Option[O], O)] = self through pipe.zipWithPrevious

  /** Alias for `self through [[pipe.zipWithPreviousAndNext]]`. */
  def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] = self through pipe.zipWithPreviousAndNext

  /** Alias for `self through [[pipe.zipWithScan]]`. */
  def zipWithScan[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan(z)(f)

  /** Alias for `self through [[pipe.zipWithScan1]]`. */
  def zipWithScan1[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan1(z)(f)
}
