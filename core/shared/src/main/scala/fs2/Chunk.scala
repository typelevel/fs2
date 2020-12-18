/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => SQueue}
import scala.collection.{mutable, IndexedSeq => GIndexedSeq, Seq => GSeq}
import scala.reflect.ClassTag
import scodec.bits.{BitVector, ByteVector}
import java.nio.{Buffer => JBuffer, ByteBuffer => JByteBuffer, CharBuffer => JCharBuffer}

import cats.{Alternative, Applicative, Eq, Eval, Monad, Traverse, TraverseFilter}
import cats.data.{Chain, NonEmptyList}
import cats.syntax.all._

/** Strict, finite sequence of values that allows index-based random access of elements.
  *
  * `Chunk`s can be created from a variety of collection types using methods on the `Chunk` companion
  * (e.g., `Chunk.array`, `Chunk.seq`, `Chunk.vector`).
  *
  * Chunks can be appended via the `++` method. The returned chunk is a composite of the input
  * chunks -- that is, there's no copying of the source chunks. For example, `Chunk(1, 2) ++ Chunk(3, 4) ++ Chunk(5, 6)`
  * returns a `Chunk.Queue(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6))`. As a result, indexed based lookup of
  * an appended chunk is `O(number of underlying chunks)`. In the worse case, where each constituent chunk
  * has size 1, indexed lookup is `O(size)`. To restore `O(1)` lookup, call `compact`, which copies all the underlying
  * chunk elements to a single array backed chunk. Note `compact` requires a `ClassTag` of the element type.
  *
  * Alternatively, a collection of chunks can be directly copied to a new array backed chunk via
  * `Chunk.concat(chunks)`. Like `compact`, `Chunk.concat` requires a `ClassTag` for the element type.
  *
  * Various subtypes of `Chunk` are exposed for efficiency reasons:
  *   - `Chunk.Singleton`
  *   - `Chunk.ArraySlice`
  *   - `Chunk.Queue`
  *
  * In particular, calling `.toArraySlice` on a chunk returns a `Chunk.ArraySlice`, which provides
  * access to the underlying backing array, along with an offset and length, referring to a slice
  * of that array.
  */
abstract class Chunk[+O] extends Serializable with ChunkPlatform[O] { self =>

  /** Returns the number of elements in this chunk. */
  def size: Int

  /** Returns the element at the specified index. Throws if index is < 0 or >= size. */
  def apply(i: Int): O

  /** Returns a chunk which consists of the elements of this chunk and the elements of
    * the supplied chunk. This operation is amortized O(1).
    */
  def ++[O2 >: O](that: Chunk[O2]): Chunk[O2] =
    if (isEmpty) that
    else
      that match {
        case that if that.isEmpty  => this
        case that: Chunk.Queue[O2] => this +: that
        case that                  => Chunk.Queue(this, that)
      }

  /** More efficient version of `filter(pf.isDefinedAt).map(pf)`. */
  def collect[O2](pf: PartialFunction[O, O2]): Chunk[O2] = {
    val b = collection.mutable.Buffer.newBuilder[O2]
    b.sizeHint(size)
    var i = 0
    while (i < size) {
      val o = apply(i)
      if (pf.isDefinedAt(o))
        b += pf(o)
      i += 1
    }
    Chunk.buffer(b.result())
  }

  /** Copies the elements of this chunk in to the specified array at the specified start index. */
  def copyToArray[O2 >: O](xs: Array[O2], start: Int = 0): Unit

  /** Converts this chunk to a chunk backed by a single array. */
  def compact[O2 >: O](implicit ct: ClassTag[O2]): Chunk.ArraySlice[O2] =
    Chunk.ArraySlice(toArray[O2], 0, size)

  /** Drops the first `n` elements of this chunk. */
  def drop(n: Int): Chunk[O] = splitAt(n)._2

  /** Drops the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
  def dropRight(n: Int): Chunk[O] = if (n <= 0) this else take(size - n)

  /** Returns a chunk that has only the elements that satisfy the supplied predicate. */
  def filter(p: O => Boolean): Chunk[O] = {
    val b = collection.mutable.Buffer.newBuilder[O]
    b.sizeHint(size)
    var i = 0
    while (i < size) {
      val e = apply(i)
      if (p(e)) b += e
      i += 1
    }
    Chunk.buffer(b.result())
  }

  /** Returns the first element for which the predicate returns true or `None` if no elements satisfy the predicate. */
  def find(p: O => Boolean): Option[O] = {
    var result: Option[O] = None
    var i = 0
    while (i < size && result.isEmpty) {
      val o = apply(i)
      if (p(o)) result = Some(o)
      i += 1
    }
    result
  }

  /** Maps `f` over the elements of this chunk and concatenates the result. */
  def flatMap[O2](f: O => Chunk[O2]): Chunk[O2] =
    if (isEmpty) Chunk.empty
    else {
      val buf = new collection.mutable.ListBuffer[Chunk[O2]]
      foreach(o => buf += f(o))
      val totalSize = buf.foldLeft(0)((acc, c) => acc + c.size)
      val b = collection.mutable.Buffer.newBuilder[O2]
      b.sizeHint(totalSize)
      buf.foreach(c => b ++= c.iterator)
      Chunk.buffer(b.result())
    }

  /** Left-folds the elements of this chunk. */
  def foldLeft[A](init: A)(f: (A, O) => A): A = {
    var i = 0
    var acc = init
    while (i < size) {
      acc = f(acc, apply(i))
      i += 1
    }
    acc
  }

  /** Returns true if the predicate passes for all elements. */
  def forall(p: O => Boolean): Boolean = {
    var i = 0
    var result = true
    while (i < size && result) {
      result = p(apply(i))
      i += 1
    }
    result
  }

  /** Invokes the supplied function for each element of this chunk. */
  def foreach(f: O => Unit): Unit = {
    var i = 0
    while (i < size) {
      f(apply(i))
      i += 1
    }
  }

  /** Gets the first element of this chunk. */
  def head: Option[O] = if (isEmpty) None else Some(apply(0))

  /** True if size is zero, false otherwise. */
  final def isEmpty: Boolean = size == 0

  /** Creates an iterator that iterates the elements of this chunk. The returned iterator is not thread safe. */
  def iterator: Iterator[O] =
    new Iterator[O] {
      private[this] var i = 0
      def hasNext = i < self.size
      def next() = { val result = apply(i); i += 1; result }
    }

  /** Returns the index of the first element which passes the specified predicate (i.e., `p(i) == true`)
    * or `None` if no elements pass the predicate.
    */
  def indexWhere(p: O => Boolean): Option[Int] = {
    var i = 0
    var result = -1
    while (result < 0 && i < size) {
      if (p(apply(i))) result = i
      i += 1
    }
    if (result == -1) None else Some(result)
  }

  /** Gets the last element of this chunk. */
  def last: Option[O] = if (isEmpty) None else Some(apply(size - 1))

  /** Creates a new chunk by applying `f` to each element in this chunk. */
  def map[O2](f: O => O2): Chunk[O2] = {
    val arr = new Array[Any](size)
    var i = 0
    while (i < size) {
      arr(i) = f(apply(i))
      i += 1
    }
    Chunk.array(arr).asInstanceOf[Chunk[O2]]
  }

  /** Maps the supplied stateful function over each element, outputting the final state and the accumulated outputs.
    * The first invocation of `f` uses `init` as the input state value. Each successive invocation uses
    * the output state of the previous invocation.
    */
  def mapAccumulate[S, O2](init: S)(f: (S, O) => (S, O2)): (S, Chunk[O2]) = {
    val arr = new Array[Any](size)
    var i = 0
    var s = init
    while (i < size) {
      val (s2, o2) = f(s, apply(i))
      arr(i) = o2
      s = s2
      i += 1
    }
    s -> Chunk.array(arr).asInstanceOf[Chunk[O2]]
  }

  /** Maps the supplied function over each element and returns a chunk of just the defined results. */
  def mapFilter[O2](f: O => Option[O2]): Chunk[O2] = {
    val sz = size
    val b = collection.mutable.Buffer.newBuilder[O2]
    b.sizeHint(sz)
    var i = 0
    while (i < sz) {
      val o = f(apply(i))
      if (o.isDefined)
        b += o.get
      i += 1
    }
    Chunk.buffer(b.result())
  }

  /** False if size is zero, true otherwise. */
  final def nonEmpty: Boolean = size > 0

  /** Creates an iterator that iterates the elements of this chunk in reverse order. The returned iterator is not thread safe. */
  def reverseIterator: Iterator[O] =
    new Iterator[O] {
      private[this] var i = self.size - 1
      def hasNext = i >= 0
      def next() = { val result = apply(i); i -= 1; result }
    }

  /** Like `foldLeft` but emits each intermediate result of `f`. */
  def scanLeft[O2](z: O2)(f: (O2, O) => O2): Chunk[O2] =
    scanLeft_(z, true)(f)._1

  /** Like `scanLeft` except the final element is emitted as a standalone value instead of as
    * the last element of the accumulated chunk.
    *
    * Equivalent to `val b = a.scanLeft(z)(f); val (c, carry) = b.splitAt(b.size - 1)`.
    */
  def scanLeftCarry[O2](z: O2)(f: (O2, O) => O2): (Chunk[O2], O2) =
    scanLeft_(z, false)(f)

  protected def scanLeft_[O2](z: O2, emitZero: Boolean)(f: (O2, O) => O2): (Chunk[O2], O2) = {
    val arr = new Array[Any](if (emitZero) size + 1 else size)
    var acc = z
    if (emitZero) arr(0) = acc
    var i = if (emitZero) 1 else 0
    var j = 0
    while (j < size) {
      acc = f(acc, apply(j))
      arr(i) = acc
      i += 1
      j += 1
    }

    Chunk.array(arr).asInstanceOf[Chunk[O2]] -> acc
  }

  /** Splits this chunk in to two chunks at the specified index. */
  def splitAt(n: Int): (Chunk[O], Chunk[O]) =
    if (n <= 0) (Chunk.empty, this)
    else if (n >= size) (this, Chunk.empty)
    else splitAtChunk_(n)

  /** Splits this chunk in to two chunks at the specified index `n`, which is guaranteed to be in-bounds. */
  protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O])

  /** Check to see if this starts with the items in the given seq
    * should be the same as take(seq.size).toChunk == Chunk.seq(seq).
    */
  def startsWith[O2 >: O](seq: Seq[O2]): Boolean =
    take(seq.size) == Chunk.seq(seq)

  /** Takes the first `n` elements of this chunk. */
  def take(n: Int): Chunk[O] = splitAt(n)._1

  /** Takes the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
  def takeRight(n: Int): Chunk[O] = if (n <= 0) Chunk.empty else drop(size - n)

  /** Copies the elements of this chunk to an array. */
  def toArray[O2 >: O: ClassTag]: Array[O2] = {
    val arr = new Array[O2](size)
    copyToArray(arr, 0)
    arr
  }

  def toArraySlice[O2 >: O](implicit ct: ClassTag[O2]): Chunk.ArraySlice[O2] =
    this match {
      case as: Chunk.ArraySlice[_] if ct.wrap.runtimeClass eq as.getClass =>
        as.asInstanceOf[Chunk.ArraySlice[O2]]
      case _ => Chunk.ArraySlice(toArray, 0, size)
    }

  /** Converts this chunk to a `java.nio.ByteBuffer`. */
  def toByteBuffer[B >: O](implicit ev: B =:= Byte): JByteBuffer = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.ArraySlice[_] =>
        JByteBuffer.wrap(c.values.asInstanceOf[Array[Byte]], c.offset, c.length)
      case c: Chunk.ByteBuffer =>
        val b = c.buf.asReadOnlyBuffer
        if (c.offset == 0 && b.position() == 0 && c.size == b.limit()) b
        else {
          (b: JBuffer).position(c.offset.toInt)
          (b: JBuffer).limit(c.offset.toInt + c.size)
          b
        }
      case _ =>
        JByteBuffer.wrap(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
    }
  }

  /** Converts this chunk to a NonEmptyList */
  def toNel: Option[NonEmptyList[O]] =
    NonEmptyList.fromList(toList)

  /** Converts this chunk to a chain. */
  def toChain: Chain[O] =
    if (isEmpty) Chain.empty
    else Chain.fromSeq(toList)

  /** Converts this chunk to a list. */
  def toList: List[O] =
    if (isEmpty) Nil
    else {
      val buf = new collection.mutable.ListBuffer[O]
      var i = 0
      while (i < size) {
        buf += apply(i)
        i += 1
      }
      buf.result()
    }

  /** Converts this chunk to a vector. */
  def toVector: Vector[O] =
    if (isEmpty) Vector.empty
    else {
      val buf = new collection.immutable.VectorBuilder[O]
      buf.sizeHint(size)
      var i = 0
      while (i < size) {
        buf += apply(i)
        i += 1
      }
      buf.result()
    }

  /** Converts this chunk to a scodec-bits ByteVector. */
  def toByteVector[B >: O](implicit ev: B =:= Byte): ByteVector = {
    val _ = ev // convince scalac that ev is used
    this match {
      case c: Chunk.ByteVectorChunk => c.toByteVector
      case other                    => ByteVector.view(other.asInstanceOf[Chunk[Byte]].toArray)
    }
  }

  /** Converts this chunk to a scodec-bits BitVector. */
  def toBitVector[B >: O](implicit ev: B =:= Byte): BitVector = {
    val _ = ev // convince scalac that ev is used
    this match {
      case c: Chunk.ByteVectorChunk => c.toByteVector.bits
      case other                    => BitVector.view(other.asInstanceOf[Chunk[Byte]].toArray)
    }
  }

  def traverse[F[_], O2](f: O => F[O2])(implicit F: Applicative[F]): F[Chunk[O2]] =
    if (isEmpty) F.pure(Chunk.empty[O2])
    else {
      // we branch out by this factor
      val width = 128
      // By making a tree here we don't blow the stack
      // even if the Chunk is very long
      // by construction, this is never called with start == end
      def loop(start: Int, end: Int): Eval[F[Chain[O2]]] =
        if (end - start <= width) {
          // Here we are at the leafs of the trees
          // we don't use map2Eval since it is always
          // at most width in size.
          var flist = f(apply(end - 1)).map(_ :: Nil)
          var idx = end - 2
          while (start <= idx) {
            flist = F.map2(f(apply(idx)), flist)(_ :: _)
            idx = idx - 1
          }
          Eval.now(flist.map(Chain.fromSeq(_)))
        } else {
          // we have width + 1 or more nodes left
          val step = (end - start) / width

          var fchain = Eval.defer(loop(start, start + step))
          var start0 = start + step
          var end0 = start0 + step

          while (start0 < end) {
            // Make sure these are vals, to avoid capturing mutable state
            // in the lazy context of Eval
            val end1 = math.min(end, end0)
            val start1 = start0
            fchain = fchain.flatMap(F.map2Eval(_, Eval.defer(loop(start1, end1)))(_.concat(_)))
            start0 = start0 + step
            end0 = end0 + step
          }
          fchain
        }

      F.map(loop(0, size).value)(Chunk.chain)
    }

  def traverseFilter[F[_], O2](f: O => F[Option[O2]])(implicit F: Applicative[F]): F[Chunk[O2]] =
    if (isEmpty) F.pure(Chunk.empty[O2])
    else {
      // we branch out by this factor
      val width = 128
      // By making a tree here we don't blow the stack
      // even if the Chunk is very long
      // by construction, this is never called with start == end
      def loop(start: Int, end: Int): Eval[F[Chain[O2]]] =
        if (end - start <= width) {
          // Here we are at the leafs of the trees
          // we don't use map2Eval since it is always
          // at most width in size.
          var flist = f(apply(end - 1)).map {
            case Some(a) => a :: Nil
            case None    => Nil
          }
          var idx = end - 2
          while (start <= idx) {
            flist = F.map2(f(apply(idx)), flist) { (optO2, list) =>
              if (optO2.isDefined) optO2.get :: list
              else list
            }
            idx = idx - 1
          }
          Eval.now(flist.map(Chain.fromSeq(_)))
        } else {
          // we have width + 1 or more nodes left
          val step = (end - start) / width

          var fchain = Eval.defer(loop(start, start + step))
          var start0 = start + step
          var end0 = start0 + step

          while (start0 < end) {
            // Make sure these are vals, to avoid capturing mutable state
            // in the lazy context of Eval
            val end1 = math.min(end, end0)
            val start1 = start0
            fchain = fchain.flatMap(F.map2Eval(_, Eval.defer(loop(start1, end1)))(_.concat(_)))
            start0 = start0 + step
            end0 = end0 + step
          }
          fchain
        }

      F.map(loop(0, size).value)(Chunk.chain)
    }

  /** Zips this chunk the the supplied chunk, returning a chunk of tuples.
    */
  def zip[O2](that: Chunk[O2]): Chunk[(O, O2)] = zipWith(that)(Tuple2.apply)

  /** Zips this chunk with the supplied chunk, passing each pair to `f`, resulting in
    * an output chunk.
    */
  def zipWith[O2, O3](that: Chunk[O2])(f: (O, O2) => O3): Chunk[O3] = {
    val sz = size.min(that.size)
    val arr = new Array[Any](sz)
    var i = 0
    while (i < sz) {
      arr(i) = f(apply(i), that.apply(i))
      i += 1
    }
    Chunk.array(arr).asInstanceOf[Chunk[O3]]
  }

  /** Zips the elements of the input chunk with its indices, and returns the new chunk.
    *
    * @example {{{
    * scala> Chunk("The", "quick", "brown", "fox").zipWithIndex.toList
    * res0: List[(String, Int)] = List((The,0), (quick,1), (brown,2), (fox,3))
    * }}}
    */
  def zipWithIndex: Chunk[(O, Int)] = {
    val arr = new Array[(O, Int)](size)
    var i = 0
    while (i < size) {
      arr(i) = (apply(i), i)
      i += 1
    }
    Chunk.array(arr)
  }

  override def hashCode: Int = {
    import util.hashing.MurmurHash3
    var i = 0
    var h = MurmurHash3.stringHash("Chunk")
    while (i < size) {
      h = MurmurHash3.mix(h, apply(i).##)
      i += 1
    }
    MurmurHash3.finalizeHash(h, i)
  }

  override def equals(a: Any): Boolean =
    a match {
      case c: Chunk[_] =>
        size == c.size && {
          var i = 0
          var result = true
          while (result && i < size) {
            result = apply(i) == c(i)
            i += 1
          }
          result
        }
      case _ => false
    }

  override def toString: String =
    iterator.mkString("Chunk(", ", ", ")")
}

object Chunk extends CollectorK[Chunk] with ChunkCompanionPlatform {

  private val empty_ : Chunk[Nothing] = new EmptyChunk
  private final class EmptyChunk extends Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = sys.error(s"Chunk.empty.apply($i)")
    def copyToArray[O2 >: Nothing](xs: Array[O2], start: Int): Unit = ()
    protected def splitAtChunk_(n: Int): (Chunk[Nothing], Chunk[Nothing]) =
      sys.error("impossible")
    override def map[O2](f: Nothing => O2): Chunk[O2] = empty
    override def toString = "empty"
  }

  /** Chunk with no elements. */
  def empty[A]: Chunk[A] = empty_

  /** Creates a chunk consisting of a single element. */
  def singleton[O](o: O): Chunk[O] = new Singleton(o)
  final class Singleton[O](val value: O) extends Chunk[O] {
    def size: Int = 1
    def apply(i: Int): O =
      if (i == 0) value else throw new IndexOutOfBoundsException()
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = xs(start) = value
    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      sys.error("impossible")
    override def map[O2](f: O => O2): Chunk[O2] = singleton(f(value))
  }

  /** Creates a chunk backed by a vector. */
  def vector[O](v: Vector[O]): Chunk[O] =
    if (v.isEmpty) empty
    else if (v.size == 1) // Use size instead of tail.isEmpty as vectors know their size
      singleton(v.head)
    else new VectorChunk(v)

  private final class VectorChunk[O](v: Vector[O]) extends Chunk[O] {
    def size = v.length
    def apply(i: Int) = v(i)
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = {
      v.copyToArray(xs, start)
      ()
    }
    override def toVector = v
    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
      val (fst, snd) = v.splitAt(n)
      vector(fst) -> vector(snd)
    }

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else vector(v.drop(n))

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else vector(v.take(n))

    override def map[O2](f: O => O2): Chunk[O2] = vector(v.map(f))
  }

  /** Creates a chunk backed by an `IndexedSeq`. */
  def indexedSeq[O](s: GIndexedSeq[O]): Chunk[O] =
    if (s.isEmpty) empty
    else if (s.size == 1)
      singleton(s.head) // Use size instead of tail.isEmpty as indexed seqs know their size
    else new IndexedSeqChunk(s)

  private final class IndexedSeqChunk[O](s: GIndexedSeq[O]) extends Chunk[O] {
    def size = s.length
    def apply(i: Int) = s(i)
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = {
      s.copyToArray(xs, start)
      ()
    }
    override def toVector = s.toVector

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else indexedSeq(s.drop(n))

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else indexedSeq(s.take(n))

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
      val (fst, snd) = s.splitAt(n)
      indexedSeq(fst) -> indexedSeq(snd)
    }
    override def map[O2](f: O => O2): Chunk[O2] = indexedSeq(s.map(f))
  }

  /** Creates a chunk from a `scala.collection.Seq`. */
  def seq[O](s: GSeq[O]): Chunk[O] = iterable(s)

  /** Creates a chunk from a `scala.collection.Iterable`. */
  def iterable[O](i: collection.Iterable[O]): Chunk[O] =
    platformIterable(i).getOrElse(i match {
      case a: mutable.ArraySeq[o]          => arraySeq[o](a).asInstanceOf[Chunk[O]]
      case v: Vector[O]                    => vector(v)
      case b: collection.mutable.Buffer[o] => buffer[o](b).asInstanceOf[Chunk[O]]
      case l: List[O] =>
        if (l.isEmpty) empty
        else if (l.tail.isEmpty) singleton(l.head)
        else {
          val bldr = collection.mutable.Buffer.newBuilder[O]
          bldr ++= l
          buffer(bldr.result())
        }
      case ix: GIndexedSeq[O] => indexedSeq(ix)
      case _ =>
        if (i.isEmpty) empty
        else {
          val itr = i.iterator
          val head = itr.next()
          if (itr.hasNext) {
            val bldr = collection.mutable.Buffer.newBuilder[O]
            bldr += head
            bldr ++= itr
            buffer(bldr.result())
          } else singleton(head)
        }
    })

  /** Creates a chunk backed by a mutable `ArraySeq`.
    */
  def arraySeq[O](arraySeq: mutable.ArraySeq[O]): Chunk[O] = {
    val arr = arraySeq.array.asInstanceOf[Array[O]]
    array(arr)(ClassTag(arr.getClass.getComponentType))
  }

  /** Creates a chunk backed by a `Chain`. */
  def chain[O](c: Chain[O]): Chunk[O] =
    if (c.isEmpty) empty
    else {
      val itr = c.iterator
      val head = itr.next()
      if (itr.hasNext) {
        val bldr = collection.mutable.Buffer.newBuilder[O]
        bldr += head
        bldr ++= itr
        buffer(bldr.result())
      } else singleton(head)
    }

  /** Creates a chunk backed by a mutable buffer. The underlying buffer must not be modified after
    * it is passed to this function.
    */
  def buffer[O](b: collection.mutable.Buffer[O]): Chunk[O] =
    if (b.isEmpty) empty
    else if (b.size == 1) singleton(b.head)
    else new BufferChunk(b)

  private final class BufferChunk[O](b: collection.mutable.Buffer[O]) extends Chunk[O] {
    def size = b.length
    def apply(i: Int) = b(i)
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = {
      b.copyToArray(xs, start)
      ()
    }
    override def toVector = b.toVector

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else buffer(b.drop(n))

    override def iterator: Iterator[O] =
      b.iterator

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else buffer(b.take(n))

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
      val (fst, snd) = b.splitAt(n)
      buffer(fst) -> buffer(snd)
    }
    override def map[O2](f: O => O2): Chunk[O2] = buffer(b.map(f))
  }

  /** Creates a chunk with the specified values. */
  def apply[O](os: O*): Chunk[O] = seq(os)

  /** Creates a chunk backed by an array. */
  def array[O: ClassTag](values: Array[O]): Chunk[O] =
    array(values, 0, values.length)

  /** Creates a chunk backed by a slice of an array. */
  def array[O: ClassTag](values: Array[O], offset: Int, length: Int): Chunk[O] =
    length match {
      case 0 => empty
      case 1 => singleton(values(offset))
      case _ => ArraySlice(values, offset, length)
    }

  case class ArraySlice[O](values: Array[O], offset: Int, length: Int)(implicit ct: ClassTag[O])
      extends Chunk[O] {
    require(
      offset >= 0 && offset <= values.size && length >= 0 && length <= values.size && offset + length <= values.size
    )

    def size = length
    def apply(i: Int) = values(offset + i)

    override def compact[O2 >: O](implicit ct: ClassTag[O2]): ArraySlice[O2] =
      if ((ct.wrap.runtimeClass eq values.getClass) && offset == 0 && length == values.length)
        this.asInstanceOf[ArraySlice[O2]]
      else super.compact

    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit =
      if (xs.getClass eq ct.wrap.runtimeClass)
        System.arraycopy(values, offset, xs, start, length)
      else {
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)
        ()
      }

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      ArraySlice(values, offset, n) -> ArraySlice(values, offset + n, length - n)

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else ArraySlice(values, offset + n, length - n)

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else ArraySlice(values, offset, n)
  }
  object ArraySlice {
    def apply[O: ClassTag](values: Array[O]): ArraySlice[O] = ArraySlice(values, 0, values.length)
  }

  sealed abstract class Buffer[A <: Buffer[A, B, C], B <: JBuffer, C: ClassTag](
      buf: B,
      val offset: Int,
      val size: Int
  ) extends Chunk[C] {
    def readOnly(b: B): B
    def buffer(b: B): A
    def get(b: B, n: Int): C
    def get(b: B, dest: Array[C], offset: Int, length: Int): B
    def duplicate(b: B): B

    def apply(i: Int): C =
      get(buf, offset + i)

    override def drop(n: Int): Chunk[C] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else {
        val second = readOnly(buf)
        (second: JBuffer).position(n + offset)
        buffer(second)
      }

    override def take(n: Int): Chunk[C] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else {
        val first = readOnly(buf)
        (first: JBuffer).limit(n + offset)
        buffer(first)
      }

    def copyToArray[O2 >: C](xs: Array[O2], start: Int): Unit = {
      val b = readOnly(buf)
      (b: JBuffer).position(offset)
      (b: JBuffer).limit(offset + size)
      val arr = new Array[C](size)
      get(b, arr, 0, size)
      arr.copyToArray(xs, start)
      ()
    }

    protected def splitAtChunk_(n: Int): (A, A) = {
      val first = readOnly(buf)
      (first: JBuffer).limit(n + offset)
      val second = readOnly(buf)
      (second: JBuffer).position(n + offset)
      (buffer(first), buffer(second))
    }

    override def toArray[O2 >: C: ClassTag]: Array[O2] = {
      val bs = new Array[C](size)
      val b = duplicate(buf)
      (b: JBuffer).position(offset)
      get(b, bs, 0, size)
      bs.asInstanceOf[Array[O2]]
    }
  }

  object CharBuffer {
    def apply(buf: JCharBuffer): CharBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JCharBuffer): CharBuffer =
      new CharBuffer(buf, buf.position, buf.remaining)
  }

  case class CharBuffer(buf: JCharBuffer, override val offset: Int, override val size: Int)
      extends Buffer[CharBuffer, JCharBuffer, Char](buf, offset, size) {
    def readOnly(b: JCharBuffer): JCharBuffer =
      b.asReadOnlyBuffer()

    def get(b: JCharBuffer, n: Int) =
      b.get(n)

    def buffer(b: JCharBuffer): CharBuffer = CharBuffer.view(b)

    override def get(b: JCharBuffer, dest: Array[Char], offset: Int, length: Int): JCharBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JCharBuffer): JCharBuffer = b.duplicate()
  }

  /** Creates a chunk backed by an char buffer, bounded by the current position and limit */
  def charBuffer(buf: JCharBuffer): Chunk[Char] = CharBuffer(buf)

  object ByteBuffer {
    def apply(buf: JByteBuffer): ByteBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JByteBuffer): ByteBuffer =
      new ByteBuffer(buf, buf.position, buf.remaining)
  }

  case class ByteBuffer private (
      buf: JByteBuffer,
      override val offset: Int,
      override val size: Int
  ) extends Buffer[ByteBuffer, JByteBuffer, Byte](buf, offset, size) {
    def readOnly(b: JByteBuffer): JByteBuffer =
      b.asReadOnlyBuffer()

    def get(b: JByteBuffer, n: Int) =
      b.get(n)

    def buffer(b: JByteBuffer): ByteBuffer = ByteBuffer.view(b)

    override def get(b: JByteBuffer, dest: Array[Byte], offset: Int, length: Int): JByteBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JByteBuffer): JByteBuffer = b.duplicate()
  }

  /** Creates a chunk backed by an byte buffer, bounded by the current position and limit */
  def byteBuffer(buf: JByteBuffer): Chunk[Byte] = ByteBuffer(buf)

  /** Creates a chunk backed by a byte vector. */
  def byteVector(bv: ByteVector): Chunk[Byte] =
    ByteVectorChunk(bv)

  private case class ByteVectorChunk(toByteVector: ByteVector) extends Chunk[Byte] {

    def apply(i: Int): Byte =
      toByteVector(i.toLong)

    def size: Int =
      toByteVector.size.toInt

    def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Byte]])
        toByteVector.copyToArray(xs.asInstanceOf[Array[Byte]], start)
      else {
        toByteVector.toIndexedSeq.copyToArray(xs)
        ()
      }

    override def drop(n: Int): Chunk[Byte] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else ByteVectorChunk(toByteVector.drop(n.toLong))

    override def take(n: Int): Chunk[Byte] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else ByteVectorChunk(toByteVector.take(n.toLong))

    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
      val (before, after) = toByteVector.splitAt(n.toLong)
      (ByteVectorChunk(before), ByteVectorChunk(after))
    }

    override def map[O2](f: Byte => O2): Chunk[O2] =
      Chunk.indexedSeq(toByteVector.toIndexedSeq.map(f))
  }

  /** Concatenates the specified sequence of chunks in to a single chunk, avoiding boxing. */
  def concat[A: ClassTag](chunks: GSeq[Chunk[A]]): Chunk[A] =
    concat(chunks, chunks.foldLeft(0)(_ + _.size))

  /** Concatenates the specified sequence of chunks in to a single chunk, avoiding boxing.
    * The `totalSize` parameter must be equal to the sum of the size of each chunk or
    * otherwise an exception may be thrown.
    */
  def concat[A: ClassTag](chunks: GSeq[Chunk[A]], totalSize: Int): Chunk[A] =
    if (totalSize == 0)
      Chunk.empty
    else {
      val arr = new Array[A](totalSize)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.array(arr)
    }

  /** Creates a chunk consisting of the elements of `queue`.
    */
  def queue[A](queue: collection.immutable.Queue[A]): Chunk[A] = seq(queue)

  /** Creates a chunk consisting of the first `n` elements of `queue` and returns the remainder.
    */
  def queueFirstN[A](
      queue: collection.immutable.Queue[A],
      n: Int
  ): (Chunk[A], collection.immutable.Queue[A]) =
    if (n <= 0) (Chunk.empty, queue)
    else if (n == 1) {
      val (hd, tl) = queue.dequeue
      (Chunk.singleton(hd), tl)
    } else {
      val bldr = collection.mutable.Buffer.newBuilder[A]
      // Note: can't use sizeHint here as `n` might be huge (e.g. Int.MaxValue)
      // and calling n.min(queue.size) has linear time complexity in queue size
      var cur = queue
      var rem = n
      while (rem > 0 && cur.nonEmpty) {
        val (hd, tl) = cur.dequeue
        bldr += hd
        cur = tl
        rem -= 1
      }
      (Chunk.buffer(bldr.result()), cur)
    }

  /** A FIFO queue of chunks that provides an O(1) size method and provides the ability to
    * take and drop individual elements while preserving the chunk structure as much as possible.
    *
    * This is similar to a queue of individual elements but chunk structure is maintained.
    */
  final class Queue[+O] private (val chunks: SQueue[Chunk[O]], val size: Int) extends Chunk[O] {
    override def iterator: Iterator[O] = chunks.iterator.flatMap(_.iterator)

    override def ++[O2 >: O](that: Chunk[O2]): Chunk[O2] =
      if (that.isEmpty) this
      else if (isEmpty) that
      else new Queue(chunks :+ that, size + that.size)

    /** Prepends a chunk to the start of this chunk queue. */
    def +:[O2 >: O](c: Chunk[O2]): Queue[O2] = new Queue(c +: chunks, c.size + size)

    /** Appends a chunk to the end of this chunk queue. */
    def :+[O2 >: O](c: Chunk[O2]): Queue[O2] = new Queue(chunks :+ c, size + c.size)

    def apply(i: Int): O = {
      if (i < 0 || i >= size) throw new IndexOutOfBoundsException()
      def go(chunks: SQueue[Chunk[O]], offset: Int): O = {
        val head = chunks.head
        if (offset < head.size) head(offset)
        else go(chunks.tail, offset - head.size)
      }
      go(chunks, i)
    }

    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = {
      def go(chunks: SQueue[Chunk[O]], offset: Int): Unit =
        if (chunks.nonEmpty) {
          val head = chunks.head
          head.copyToArray(xs, offset)
          go(chunks.tail, offset + head.size)
        }
      go(chunks, start)
    }

    override def take(n: Int): Queue[O] =
      if (n <= 0) Queue.empty
      else if (n >= size) this
      else {
        @tailrec
        def go(acc: SQueue[Chunk[O]], rem: SQueue[Chunk[O]], toTake: Int): Queue[O] =
          if (toTake <= 0) new Queue(acc, n)
          else {
            val (next, tail) = rem.dequeue
            val nextSize = next.size
            if (nextSize <= toTake) go(acc :+ next, tail, toTake - nextSize)
            else new Queue(acc :+ next.take(toTake), n)
          }
        go(SQueue.empty, chunks, n)
      }

    override def drop(n: Int): Queue[O] =
      if (n <= 0) this
      else if (n >= size) Queue.empty
      else {
        @tailrec
        def go(rem: SQueue[Chunk[O]], toDrop: Int): Queue[O] =
          if (toDrop <= 0) new Queue(rem, size - n)
          else {
            val next = rem.head
            val nextSize = next.size
            if (nextSize <= toDrop) go(rem.tail, toDrop - nextSize)
            else new Queue(next.drop(toDrop) +: rem.tail, size - n)
          }
        go(chunks, n)
      }

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
      @tailrec
      def go(taken: SQueue[Chunk[O]], rem: SQueue[Chunk[O]], toDrop: Int): (Queue[O], Queue[O]) =
        if (toDrop <= 0) (new Queue(taken, n), new Queue(rem, size - n))
        else {
          val next = rem.head
          val nextSize = next.size
          if (nextSize <= toDrop) go(taken :+ next, rem.tail, toDrop - nextSize)
          else {
            val (pfx, sfx) = next.splitAtChunk_(toDrop)
            (new Queue(taken :+ pfx, n), new Queue(sfx +: rem.tail, size - n))
          }
        }
      go(SQueue.empty, chunks, n)
    }

    override def startsWith[O2 >: O](seq: Seq[O2]): Boolean = {
      val iter = seq.iterator

      @annotation.tailrec
      def check(chunks: SQueue[Chunk[O]], idx: Int): Boolean =
        if (!iter.hasNext) true
        else if (chunks.isEmpty) false
        else {
          val chead = chunks.head
          if (chead.size == idx) check(chunks.tail, 0)
          else {
            val qitem = chead(idx)
            val iitem = iter.next()
            if (iitem == qitem)
              check(chunks, idx + 1)
            else false
          }
        }

      check(chunks, 0)
    }
  }

  object Queue {
    private val empty_ = new Queue(collection.immutable.Queue.empty, 0)
    def empty[O]: Queue[O] = empty_.asInstanceOf[Queue[O]]
    def singleton[O](c: Chunk[O]): Queue[O] = new Queue(collection.immutable.Queue(c), c.size)
    def apply[O](chunks: Chunk[O]*): Queue[O] =
      if (chunks.isEmpty) empty
      else chunks.tail.foldLeft(singleton(chunks.head))(_ :+ _)
  }

  def newBuilder[O]: Collector.Builder[O, Chunk[O]] =
    new Collector.Builder[O, Chunk[O]] {
      private[this] var acc = Chunk.empty[O]
      def +=(c: Chunk[O]): Unit = acc = acc ++ c
      def result: Chunk[O] = acc
    }

  implicit def eqInstance[A](implicit A: Eq[A]): Eq[Chunk[A]] =
    new Eq[Chunk[A]] {
      def eqv(c1: Chunk[A], c2: Chunk[A]) =
        c1.size == c2.size && {
          var i = 0
          var result = true
          while (result && i < c1.size) {
            result = A.eqv(c1(i), c2(i))
            i += 1
          }
          result
        }
    }

  /** `Traverse`, `Monad`, `Alternative`, and `TraverseFilter` instance for `Chunk`.
    *
    * @example {{{
    * scala> import cats.syntax.all._, scala.util._
    * scala> Chunk("1", "2", "NaN").mapFilter(s => Try(s.toInt).toOption)
    * res0: fs2.Chunk[Int] = Chunk(1, 2)
    * }}}
    */
  implicit val instance
      : Traverse[Chunk] with Monad[Chunk] with Alternative[Chunk] with TraverseFilter[Chunk] =
    new Traverse[Chunk] with Monad[Chunk] with Alternative[Chunk] with TraverseFilter[Chunk] {
      override def foldLeft[A, B](fa: Chunk[A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
      override def foldRight[A, B](fa: Chunk[A], b: Eval[B])(
          f: (A, Eval[B]) => Eval[B]
      ): Eval[B] = {
        def go(i: Int): Eval[B] =
          if (i < fa.size) f(fa(i), Eval.defer(go(i + 1)))
          else b
        go(0)
      }
      override def toList[A](fa: Chunk[A]): List[A] = fa.toList
      override def isEmpty[A](fa: Chunk[A]): Boolean = fa.isEmpty
      override def empty[A]: Chunk[A] = Chunk.empty
      override def pure[A](a: A): Chunk[A] = Chunk.singleton(a)
      override def map[A, B](fa: Chunk[A])(f: A => B): Chunk[B] = fa.map(f)
      override def flatMap[A, B](fa: Chunk[A])(f: A => Chunk[B]): Chunk[B] = fa.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A => Chunk[Either[A, B]]): Chunk[B] = {
        // Based on the implementation of tailRecM for Vector from cats, licensed under MIT
        val buf = collection.mutable.Buffer.newBuilder[B]
        var state = List(f(a).iterator)
        @tailrec
        def go(): Unit =
          state match {
            case Nil => ()
            case h :: tail if h.isEmpty =>
              state = tail
              go()
            case h :: tail =>
              h.next() match {
                case Right(b) =>
                  buf += b
                  go()
                case Left(a) =>
                  state = (f(a).iterator) :: h :: tail
                  go()
              }
          }
        go()
        Chunk.buffer(buf.result())
      }
      override def combineK[A](x: Chunk[A], y: Chunk[A]): Chunk[A] =
        x ++ y
      override def traverse: Traverse[Chunk] = this
      override def traverse[F[_], A, B](
          fa: Chunk[A]
      )(f: A => F[B])(implicit F: Applicative[F]): F[Chunk[B]] = fa.traverse(f)
      override def traverseFilter[F[_], A, B](
          fa: Chunk[A]
      )(f: A => F[Option[B]])(implicit F: Applicative[F]): F[Chunk[B]] = fa.traverseFilter(f)
      override def mapFilter[A, B](fa: Chunk[A])(f: A => Option[B]): Chunk[B] = fa.mapFilter(f)
    }
}
