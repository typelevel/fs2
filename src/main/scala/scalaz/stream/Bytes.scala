package scalaz.stream

import java.nio.ByteBuffer
import java.nio.charset.Charset
import scala._
import scala.annotation.tailrec
import scala.collection.immutable.IndexedSeq
import scala.collection.{mutable, IndexedSeqOptimized}
import scala.reflect.ClassTag
import scalaz.{IsEmpty, Monoid}

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
  def compact: Bytes

  /** returns true, if internal representation is in single Array[Byte] **/
  def compacted: Boolean

  /**
   * Effectively decodes content of bytes with supplied charset to string
   * @param chs Charset to use, default is UTF-8
   */
  def decode(chs: Charset = Charset.forName("UTF-8")): String

  /**
   * Converts Bytes to nio ByteBuffer.
   * Please note the resulting ByteBuffer is read-only.
   * Execution of this may allocate new Buffer
   */
  def asByteBuffer: ByteBuffer

  /** converts all segments of this Bytes to read-only nio ByteBuffers **/
  def asByteBuffers: Iterable[ByteBuffer]


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
  , val length: Int
  ) extends Bytes {


  def apply(idx: Int): Byte = src(pos + idx)
  def append(that: Bytes): Bytes =
    if (that.isEmpty) this
    else if (this.length == 0) that
    else that match {
      case one: Bytes1  => BytesN(Vector(this, one))
      case many: BytesN => BytesN(this +: many.seg)
    }


  def compact: Bytes =
    if (compacted) this
    else Bytes1(this.toArray, 0, length)

  def compacted: Boolean = pos == 0 && src.length == length

  def decode(chs: Charset): String =
    if (compacted) new String(src, chs)
    else new String(toArray, chs)

  def asByteBuffer: ByteBuffer = {
    val buffer = ByteBuffer.wrap(src, pos, length).asReadOnlyBuffer
    if (buffer.remaining < src.length) buffer.slice
    else buffer
  }

  def asByteBuffers: Iterable[ByteBuffer] = Seq(asByteBuffer)

  override def lastIndexWhere(p: (Byte) => Boolean, end: Int): Int = {
    if (pos + end >= src.length) throw new IndexOutOfBoundsException(s"size: $length, end: $end, must end < size")
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
    else Bytes1(src, pos, (idx - pos) min length)
  }


  override def dropWhile(p: (Byte) => Boolean): Bytes = {
    val idx = src.indexWhere(!p(_), pos)
    if (idx < 0) Bytes.empty
    else Bytes1(src, idx, (length - (idx - pos)) max 0)
  }

  //(c takeWhile p, c dropWhile p)
  override def span(p: (Byte) => Boolean): (Bytes, Bytes) = {
    val idx = src.indexWhere(!p(_), pos)
    if (idx < 0) (this, Bytes.empty)
    else (Bytes1(src, pos, (idx - pos) min length), Bytes1(src, idx, (length - (idx - pos)) max 0))
  }

  override def indexWhere(p: (Byte) => Boolean): Int = indexWhere(p, 0)
  override def indexWhere(p: (Byte) => Boolean, from: Int): Int = {
    if (length > 0) {
      val idx = src.indexWhere(p, pos + from)
      if (idx >= pos && (idx - pos) < length) idx - pos else -1
    } else {
      -1
    }
  }

  override def indexOf[B >: Byte](elem: B): Int =
    src.indexOf(elem, pos) - pos max -1

  override def slice(from: Int, until: Int): Bytes = {
    if (from >= length) Bytes.empty
    else Bytes1(src, pos + from, (until - from) min length)
  }


  /** copy content of the Bytes to form new Array[Byte] **/
  def toArray: Array[Byte] = toArray[Byte]

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = {
    val a = Array.ofDim[B](length)
    Array.copy(src, pos, a, 0, length)
    a
  }
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    val l1 = math.min(len, length)
    val l2 = if (xs.length - start < l1) math.max(xs.length - start, 0) else l1
    if (l2 > 0) Array.copy(src, pos, xs, start, l2)
  }

  override def foreach[@specialized U](f: (Byte) => U): Unit =
    for (i <- pos until pos + length) {f(src(i)) }

  override def toString(): String =
    s"Bytes1: pos=$pos, length=$length, src: ${src.take(10 min length).mkString("(",",",if(length > 10) "..." else ")" )}"
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
  def compact: Bytes = Bytes1(toArray, 0, length)


  /** returns true, if internal representation is in single Array[Byte] **/
  def compacted: Boolean = false


  def decode(chs: Charset): String = new String(toArray, chs)


  def asByteBuffer: ByteBuffer = compact.asByteBuffer

  def asByteBuffers: Iterable[ByteBuffer] = seg.map(_.asByteBuffer)

  lazy val length: Int = {
    if (seg.size == 0) 0
    else seg.foldLeft(0)(_ + _.length)
  }


  def apply(idx: Int): Byte = {

    val it = seg.iterator
    @tailrec
    def go(at: Int): Byte = {
      if (it.hasNext) {
        val one = it.next()
        val cur = idx - at
        if (cur >= one.length) go(at + one.length)
        else one(cur)
      }
      else throw new IndexOutOfBoundsException(s"Bytes has size of $length, but got idx of $idx")
    }

    go(0)
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

    val it = seg.reverseIterator

    @tailrec
    def go(at:Int):Int = {
      if (it.hasNext) {
        val b1 = it.next
        if (at - b1.size > end) go(at - b1.size)
        else {
          val end1 = (b1.size - 1) min (b1.size - (at - end) - 1)
          val idx = b1.lastIndexWhere(p, end1)
          if (idx < 0) go(at - b1.size)
          else (at - b1.size + idx) + 1
        }
      } else -1
    }
    go(length-1)

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
        case Some(b1) if at + b1.size <= from  => go(at+b1.size,Vector(),rem.tail)
        case Some(b1) =>
          val start = (from - at) max 0
          val end = (until - at ) min b1.size
          if (end <= 0) BytesN(acc)
          else go(at + b1.size, acc :+ b1.slice(start,end).asInstanceOf[Bytes1], rem.tail)
        case None if acc.isEmpty => Bytes.empty
        case None => BytesN(acc)
      }
    }

    if (from > until) throw new IndexOutOfBoundsException(s"from must <= until: $from > $until")
    go(0, Vector(), seg)
  }

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = {
    val ca = Array.ofDim[B](length)
    copyToArray(ca)
    ca
  }

  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit = {
    val it = seg.iterator
    @tailrec
    def go(at: Int): Unit = if (at < len && it.hasNext) {
      val b1 = it.next
      b1.copyToArray(xs, start + at, len - at)
      go(at + b1.length)
    }

    go(0)
  }

  override def foreach[@specialized U](f: (Byte) => U): Unit = seg.foreach(_.foreach(f))
}

object BytesN {

  //todo: place instances here

}



object Bytes extends BytesInstances {

  val empty: Bytes = Bytes1(Array.emptyByteArray, 0, 0)

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
    val sz = if (size == Int.MaxValue) a.size else size
    val ca = Array.ofDim[Byte](sz)
    Array.copy(a, 0, ca, 0, sz)
    Bytes1(ca, 0, sz)
  }

  /**
   * Creates immutable view of supplied byteBuffers.
   * Note this will copy content of supplied byte buffers to guarantee immutability
   * @param bb   first ByteBuffer
   * @param bbn  next ByteBuffers
   * @return
   */
  def of(bb: ByteBuffer, bbn: ByteBuffer*): Bytes = of(bb +: bbn)

  /**
   * Creates immutable view of supplied sequence of bytebuffers
   * Note this will copy content of supplied byte buffers to guarantee immutability
   * @param bbn
   * @return
   */
  def of(bbn: Seq[ByteBuffer]): Bytes = {
    def copyOne(bb: ByteBuffer): Bytes1 = {
      val a = Array.ofDim[Byte](bb.remaining())
      bb.get(a)
      Bytes1(a, 0, a.length)
    }

    if (bbn.isEmpty) Bytes.empty
    else if (bbn.size == 1) copyOne(bbn.head)
    else BytesN(bbn.map(copyOne).toVector)
  }

  /**
   * Like `of` only it does not copy `a`. Please note using this is unsafe if you will mutate the buffer
   * outside the `Bytes`
   * @param a
   * @param pos when specified, indicates where the Bytes shall start reading from array
   * @param size when specified, indicates how much Bytes shall read from supplied array,
   * @return
   */
  def unsafe(a: Array[Byte], pos: Int = 0, size: Int = Int.MaxValue): Bytes = {
    val sz = if (size == Int.MaxValue) a.size else size
    Bytes1(a, pos, sz)
  }
}

sealed abstract class BytesInstances {
  implicit val bytesInstance = new IsEmpty[({ type λ[α] = Bytes })#λ] with Monoid[Bytes] {
    type BA[α] = Bytes
    def empty[A] = Bytes.empty
    def plus[A](f1: BA[A], f2: => BA[A]) = f1 ++ f2
    def isEmpty[A](b: BA[A]) = b.isEmpty
    def append(f1: Bytes, f2: => Bytes) = f1 ++ f2
    def zero = Bytes.empty
  }
}
