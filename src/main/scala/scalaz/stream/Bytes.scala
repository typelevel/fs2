package scalaz.stream

import scala._
import scala.annotation.tailrec
import scala.collection.immutable.IndexedSeq
import scala.collection.{mutable, IndexedSeqOptimized}
import scala.reflect.ClassTag

/**
 * Simple `immutability` wrapper to allow easy and effective working with Array[Byte]
 *
 * Most operations are optimized to not allow byte array copying.
 *
 */
sealed trait Bytes extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, Bytes] {

  override protected[this] def newBuilder: BytesBuilder = new BytesBuilder

  /**
   * Efficiently concatenates two Bytes together.
   * This operates in constant time and does not require copying of underlying array of Byte
   * @param other
   * @return
   */
  def append(other: Bytes): Bytes

  /** Alias for append **/
  def ++(other: Bytes): Bytes = append(other)

  /** copy content of the Bytes to form new Array[Byte] **/
  def toArray: Array[Byte]

  /** shrinks internally segmented source to form single source of Array[Byte] **/
  def shrink: Bytes

  /** returns true, if internal representation is in single Array[Byte] **/
  def shrinked: Boolean

  override def head: Byte = apply(0)
  override def tail: Bytes = drop(1)
  override def last: Byte = apply(length - 1)
  override def init: Bytes = dropRight(1)
  override def take(n: Int): Bytes = slice(0, n)
  override def takeRight(n: Int): Bytes = slice(length - n, length)
  override def drop(n: Int): Bytes = slice(n, length)
  override def dropRight(n: Int): Bytes = slice(0, length - n)
  override def takeWhile(p: Byte => Boolean): Bytes = sys.error("implemented in Bytes1 or BytesN")
  override def dropWhile(p: Byte => Boolean): Bytes = sys.error("implemented in Bytes1 or BytesN")
  override def span(p: Byte => Boolean): (Bytes, Bytes) = sys.error("implemented in Bytes1 or BytesN")
  override def splitAt(n: Int): (Bytes, Bytes) = (take(n), drop(n))
  override def indexWhere(p: Byte => Boolean): Int = sys.error("implemented in Bytes1 or BytesN")
  override def indexWhere(p: (Byte) => Boolean, from: Int): Int = sys.error("implemented in Bytes1 or BytesN")
  override def lastIndexWhere(p: (Byte) => Boolean, end: Int): Int = sys.error("implemented in Bytes1 or BytesN")
  override def indexOf[B >: Byte](elem: B): Int = sys.error("implemented in Bytes1 or BytesN")
  override def slice(from: Int, until: Int): Bytes = sys.error("implemented in Bytes1 or BytesN")
  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = sys.error("implemented in Bytes1 or BytesN")
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = sys.error("implemented in Bytes1 or BytesN")
  override def foreach[@specialized U](f: Byte => U): Unit = sys.error("implemented in Bytes1 or BytesN")


}

final class BytesBuilder extends mutable.Builder[Byte, Bytes] {
  val ab = mutable.ArrayBuilder.make[Byte]
  def +=(elem: Byte): this.type = { ab += elem; this }
  def clear(): Unit = ab.clear()
  def result(): Bytes = {
    val src = ab.result()
    Bytes1(src, 0, src.length)
  }
}


/** Bytes instance with only one segment **/
final case class Bytes1 private[stream](
  private[stream] val src: Array[Byte]
  , private[stream] val pos: Int
  , private[stream] val sz: Int
  ) extends Bytes {

  def length: Int = sz
  def apply(idx: Int): Byte = src(pos + idx)
  def append(that: Bytes): Bytes =
    if (that.isEmpty) this
    else if (this.length == 0) that
    else that match {
      case one: Bytes1  => BytesN(Vector(this, one))
      case many: BytesN => BytesN(this +: many.seg)
    }


  def shrink: Bytes =
    if (shrinked) this
    else Bytes1(this.toArray, 0, sz)

  def shrinked: Boolean = pos == 0 && length == sz


  override def lastIndexWhere(p: (Byte) => Boolean, end: Int): Int = {
    if (pos + end >= src.length) throw new IndexOutOfBoundsException(s"size: $sz, end: $end, must end < size")
    else {
      val idx = src.lastIndexWhere(p, pos + end)
      if (idx >= pos) idx - pos else -1
    }
  }

  override def lastIndexWhere(p: (Byte) => Boolean): Int =
    lastIndexWhere(p, size-1)

  override def takeWhile(p: (Byte) => Boolean): Bytes = {
    val idx = src.indexWhere(!p(_), pos)
    if (idx < 0) this
    else Bytes1(src, pos, (idx - pos) min sz)
  }


  override def dropWhile(p: (Byte) => Boolean): Bytes = {
    val idx = src.indexWhere(!p(_), pos)
    if (idx < 0) Bytes.empty
    else Bytes1(src, idx, (sz - (idx - pos)) max 0)
  }

  //(c takeWhile p, c dropWhile p)
  override def span(p: (Byte) => Boolean): (Bytes, Bytes) = {
    val idx = src.indexWhere(!p(_), pos)
    if (idx < 0) (this, Bytes.empty)
    else (Bytes1(src, pos, (idx - pos) min sz), Bytes1(src, idx, (sz - (idx - pos)) max 0))
  }

  override def indexWhere(p: (Byte) => Boolean): Int = indexWhere(p, 0)
  override def indexWhere(p: (Byte) => Boolean, from: Int): Int = {
    if (sz > 0) {
      val idx = src.indexWhere(p, pos + from)
      if (idx >= pos && (idx - pos) < sz) idx - pos else -1
    } else {
      -1
    }
  }

  override def indexOf[B >: Byte](elem: B): Int =
    src.indexOf(elem, pos) - pos max -1

  override def slice(from: Int, until: Int): Bytes = {
    if (from >= sz) Bytes.empty
    else Bytes1(src, pos + from, (until - from) min sz)
  }


  /** copy content of the Bytes to form new Array[Byte] **/
  def toArray: Array[Byte] = toArray[Byte]

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = {
    val a = Array.ofDim(sz)
    Array.copy(src, pos, a, 0, sz)
    a
  }
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    val l1 = len min sz
    val l2 = if (xs.length - start < l1) xs.length - start max 0 else l1
    Array.copy(repr, pos, xs, start, l2)
  }
  override def foreach[@specialized U](f: (Byte) => U): Unit =
    for (i <- pos until pos + sz) {f(src(i)) }


}

object Bytes1 {

  //todo: place instances here
}

/** Bytes instance with N segments **/
final case class BytesN private[stream](private[stream] val seg: Vector[Bytes1]) extends Bytes {

  def append(that: Bytes): Bytes =
    if (that.isEmpty) this
    else if (this.length == 0) that
    else that match {
      case one: Bytes1  => BytesN(this.seg :+ one)
      case many: BytesN => BytesN(this.seg ++ many.seg)
    }

  /** copy content of the Bytes to form new Array[Byte] **/
  def toArray: Array[Byte] = toArray[Byte]

  /** shrinks internally segmented source to form single source of Array[Byte] **/
  def shrink: Bytes = {
    Bytes1(seg.map(_.toArray).flatten.toArray, 0, length)
  }

  /** returns true, if internal representation is in single Array[Byte] **/
  def shrinked: Boolean = false


  lazy val length: Int = {
    if (seg.size == 0) 0
    else seg.map(_.length).reduce(_ + _)
  }


  def apply(idx: Int): Byte = {
    @tailrec
    def go(at: Int, rem: Vector[Bytes1]): Byte = {
      rem.headOption match {
        case Some(one) =>
          val cur = idx - at
          if (cur >= one.sz) go(at + one.sz, rem.tail)
          else one(cur)
        case None      => throw new IndexOutOfBoundsException(s"Bytes has size of $length, but got idx of $idx")
      }
    }

    go(0, seg)
  }


  override def takeWhile(p: (Byte) => Boolean): Bytes =
    indexWhere0(!p(_), 0) match {
      case Some((idx, l, _)) => BytesN(l)
      case None              => this
    }

  override def dropWhile(p: (Byte) => Boolean): Bytes =
    indexWhere0(!p(_), 0) match {
      case Some((idx, _, r)) => BytesN(r)
      case None              => Bytes.empty
    }

  override def span(p: (Byte) => Boolean): (Bytes, Bytes) =
    indexWhere0(!p(_), 0) match {
      case Some((idx, l, r)) => (BytesN(l),BytesN(r))
      case None              => (this,Bytes.empty)
    }


  override def indexWhere(p: (Byte) => Boolean): Int = indexWhere(p, 0)
  override def indexWhere(p: (Byte) => Boolean, from: Int): Int = {
    indexWhere0(p, from) match {
      case Some((idx, _, _)) => idx
      case None              => -1
    }
  }

  //helper to scan for condition p, and returns optionally splitted
  // vestor of bytes where that condition yields to true.
  // if splitted in middle of Bytes1, that is split too..
  private def indexWhere0(p: (Byte) => Boolean, from: Int): Option[(Int, Vector[Bytes1], Vector[Bytes1])] = {
    @tailrec
    def go(at: Int, acc: Vector[Bytes1], rem: Vector[Bytes1]): Option[(Int, Vector[Bytes1], Vector[Bytes1])] = {
      rem.headOption match {
        case Some(b1) =>
          if (at + b1.size < from) go(at + b1.size, Vector(), rem.tail)
          else {
            val start = (from - at) max 0
            val idx = b1.indexWhere(p, start)
            if (idx < 0) go(at + b1.size, acc :+ b1.drop(start).asInstanceOf[Bytes1], rem.tail)
            else {
              val (l, r) = b1.splitAt(idx).asInstanceOf[(Bytes1, Bytes1)]
              Some((at + idx, acc :+ l, r +: rem.tail))
            }
          }

        case _ => None
      }
    }
    go(0, Vector(), seg)
  }


  override def lastIndexWhere(p: (Byte) => Boolean, end: Int): Int = {
    @tailrec
    def go(at:Int, rem:Vector[Bytes1]):Int = {

      rem.lastOption match {
        case Some(b1) if (at - b1.size > end)  =>
          go(at - b1.size , rem.init)
        case Some(b1) =>
            val end1 = (b1.size - 1) min (b1.size - (at - end) - 1)
            val idx = b1.lastIndexWhere(p, end1)
            if (idx < 0) go(at - b1.size, rem.init)
            else (at - b1.size + idx) + 1

        case None => -1
      }
    }
    go(length-1, seg)

  }
  override def lastIndexWhere(p: (Byte) => Boolean): Int = lastIndexWhere(p, length-1)

  override def indexOf[B >: Byte](elem: B): Int =
    indexWhere0(_ == elem, 0) match {
      case Some((idx, _, _)) => idx
      case None              => -1
    }


  override def slice(from: Int, until: Int): Bytes = {
    @tailrec
    def go(at: Int, acc: Vector[Bytes1], rem: Vector[Bytes1]): Bytes = {
      rem.headOption match {
        case Some(b1) if at + b1.size < from  => go(at+b1.size,Vector(),rem.tail)
        case Some(b1) =>
          val start = (from - at) max 0
          val end = (until - at ) min b1.size
          if (end <= 0) BytesN(acc)
          else go(at + b1.size, acc :+ b1.slice(start,end).asInstanceOf[Bytes1], rem.tail)
        case None if acc.isEmpty => Bytes.empty
        case None if acc.size == 1 => acc.head
        case None => BytesN(acc)
      }
    }

    if (from > until) throw new IndexOutOfBoundsException(s"from must <= until: $from > $until")
    go(0, Vector(), seg)
  }

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = shrink.toArray[B]
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    @tailrec
    def go(at: Int, rem: Vector[Bytes1]): Unit = {
      rem.headOption match {
        case Some(b1) =>
          b1.copyToArray(xs, at + start, len - at)
          if (at + b1.sz < len) go(at + b1.sz, rem.tail)
          else ()

        case None => //no-op
      }
    }
    go(start, seg)
  }
  override def foreach[@specialized U](f: (Byte) => U): Unit = {
    @tailrec
    def go(rem: Vector[Bytes1]): Unit = {
      rem.headOption match {
        case Some(b1) =>
          b1.foreach(f)
          go(rem.tail)
        case None     => ()
      }
    }
    go(seg)
  }

}

object BytesN {

  //todo: place instances here

}



object Bytes {

  val empty = Bytes1(Array.emptyByteArray, 0, 0)

  /**
   * Creates immutable view of supplied array and wraps it into bytes.
   * Please note this will copy supplied array to guarantee immutability.
   * If, you want to reuse array of bytes use `CopyOnWrite.bytes` and then `modify` if that array may get mutated
   * or just use unsafe version.
   * @param a array to back this Bytes
   * @param pos when specified, indicates where the Bytes shall start reading from array
   * @param size when specified, indicates how much Bytes shall read from supplied array,
   *             if this is greater that size of array it will get truncated
   * @return
   */
  def of(a: Array[Byte], pos: Int = 0, size: Int = Int.MaxValue): Bytes = {
    val ca = Array.ofDim[Byte](a.size)
    Array.copy(a, 0, ca, 0, size)
    Bytes1(ca, 0, size)
  }

  /**
   * Like `ofArray` only it does not copy `a`. Please note using this is unsafe if you will mutate the buffer
   * outside the `Bytes`
   * @param a
   * @param pos when specified, indicates where the Bytes shall start reading from array
   * @param size when specified, indicates how much Bytes shall read from supplied array,
   * @return
   */
  def unsafe(a: Array[Byte], pos: Int = 0, size: Int = Int.MaxValue): Bytes = {
    Bytes1(a, pos, size min a.size)
  }

  //todo: place instances here
}