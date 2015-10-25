package fs2

trait Process1Ops[+F[_],+O] { self: Stream[F,O] =>

  // note: these are in alphabetical order

  /** Alias for `self pipe [[process1.chunks]]`. */
  def chunks: Stream[F,Chunk[O]] = self pipe process1.chunks

  /** Alias for `self pipe [[process1.delete]]`. */
  def delete(f: O => Boolean): Stream[F,O] = self pipe process1.delete(f)

  /** Alias for `self pipe [[process1.drop]]`. */
  def drop(n: Int): Stream[F,O] = self pipe process1.drop(n)
  
  /** Alias for `self pipe [[process1.dropWhile]]` */
  def dropWhile(p: O => Boolean): Stream[F,O] = self pipe process1.dropWhile(p)
  
  /** Alias for `self pipe [[process1.filter]]`. */
  def filter(f: O => Boolean): Stream[F,O] = self pipe process1.filter(f)

  /** Alias for `self pipe [[process1.last]]`. */
  def last: Stream[F,Option[O]] = self pipe process1.last

  /** Alias for `self pipe [[process1.mapChunks]](f)`. */
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] = self pipe process1.mapChunks(f)

  /** Alias for `self pipe [[process1.take]](n)`. */
  def take(n: Long): Stream[F,O] = self pipe process1.take(n)
  
  /** Alias for `self pipe [[process1.takeWhile]]`. */
  def takeWhile(p: O => Boolean): Stream[F,O] = self pipe process1.takeWhile(p)
 
  /** Alias for `self pipe [[process1.unchunk]]`. */
  def unchunk: Stream[F,O] = self pipe process1.unchunk
  
  /** Alias for `self pipe [[process1.zipWithIndex]]` .*/
  def zipWithIndex: Stream[F, (O, Int)] = self pipe process1.zipWithIndex
}
