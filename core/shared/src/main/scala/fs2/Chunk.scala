package fs2

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => SQueue}
import scala.collection.{IndexedSeq => GIndexedSeq, Seq => GSeq}
import scala.reflect.ClassTag
import scodec.bits.ByteVector
import java.nio.{
  Buffer => JBuffer,
  ByteBuffer => JByteBuffer,
  CharBuffer => JCharBuffer,
  DoubleBuffer => JDoubleBuffer,
  FloatBuffer => JFloatBuffer,
  IntBuffer => JIntBuffer,
  LongBuffer => JLongBuffer,
  ShortBuffer => JShortBuffer
}

import cats.{Applicative, Eq, Eval, Functor, FunctorFilter, Monad, Traverse}
import cats.data.{Chain, NonEmptyList}
import cats.implicits._
import fs2.internal.ArrayBackedSeq

/**
  * Strict, finite sequence of values that allows index-based random access of elements.
  *
  * `Chunk`s can be created from a variety of collection types using methods on the `Chunk` companion
  * (e.g., `Chunk.vector`, `Chunk.seq`, `Chunk.array`). Additionally, the `Chunk` companion
  * defines a subtype of `Chunk` for each primitive type, using an unboxed primitive array.
  * To work with unboxed arrays, use methods like `toBytes` to convert a `Chunk[Byte]` to a `Chunk.Bytes`
  * and then access the array directly.
  *
  * The operations on `Chunk` are all defined strictly. For example, `c.map(f).map(g).map(h)` results in
  * intermediate chunks being created (1 per call to `map`).
  */
abstract class Chunk[+O] extends Serializable { self =>

  /** Returns the number of elements in this chunk. */
  def size: Int

  /** Returns the element at the specified index. Throws if index is < 0 or >= size. */
  def apply(i: Int): O

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
    Chunk.buffer(b.result)
  }

  /** Copies the elements of this chunk in to the specified array at the specified start index. */
  def copyToArray[O2 >: O](xs: Array[O2], start: Int = 0): Unit

  /** Drops the first `n` elements of this chunk. */
  def drop(n: Int): Chunk[O] = splitAt(n)._2

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
    Chunk.buffer(b.result)
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
      Chunk.buffer(b.result)
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
  def iterator: Iterator[O] = new Iterator[O] {
    private[this] var i = 0
    def hasNext = i < self.size
    def next = { val result = apply(i); i += 1; result }
  }

  /**
    * Returns the index of the first element which passes the specified predicate (i.e., `p(i) == true`)
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
    val b = collection.mutable.Buffer.newBuilder[O2]
    b.sizeHint(size)
    var i = 0
    while (i < size) {
      b += f(apply(i))
      i += 1
    }
    Chunk.buffer(b.result)
  }

  /**
    * Maps the supplied stateful function over each element, outputting the final state and the accumulated outputs.
    * The first invocation of `f` uses `init` as the input state value. Each successive invocation uses
    * the output state of the previous invocation.
    */
  def mapAccumulate[S, O2](init: S)(f: (S, O) => (S, O2)): (S, Chunk[O2]) = {
    val b = collection.mutable.Buffer.newBuilder[O2]
    b.sizeHint(size)
    var i = 0
    var s = init
    while (i < size) {
      val (s2, o2) = f(s, apply(i))
      b += o2
      s = s2
      i += 1
    }
    s -> Chunk.buffer(b.result)
  }

  /** False if size is zero, true otherwise. */
  final def nonEmpty: Boolean = size > 0

  /** Creates an iterator that iterates the elements of this chunk in reverse order. The returned iterator is not thread safe. */
  def reverseIterator: Iterator[O] = new Iterator[O] {
    private[this] var i = self.size - 1
    def hasNext = i >= 0
    def next = { val result = apply(i); i -= 1; result }
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

  protected def scanLeft_[O2](z: O2, emitFinal: Boolean)(f: (O2, O) => O2): (Chunk[O2], O2) = {
    val b = collection.mutable.Buffer.newBuilder[O2]
    b.sizeHint(if (emitFinal) size + 1 else size)
    var acc = z
    var i = 0
    while (i < size) {
      acc = f(acc, apply(i))
      b += acc
      i += 1
    }
    if (emitFinal) b += acc
    Chunk.buffer(b.result) -> acc
  }

  /** Splits this chunk in to two chunks at the specified index. */
  def splitAt(n: Int): (Chunk[O], Chunk[O]) =
    if (n <= 0) (Chunk.empty, this)
    else if (n >= size) (this, Chunk.empty)
    else splitAtChunk_(n)

  /** Splits this chunk in to two chunks at the specified index `n`, which is guaranteed to be in-bounds. */
  protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O])

  /** Takes the first `n` elements of this chunk. */
  def take(n: Int): Chunk[O] = splitAt(n)._1

  /** Copies the elements of this chunk to an array. */
  def toArray[O2 >: O: ClassTag]: Array[O2] = {
    val arr = new Array[O2](size)
    var i = 0
    while (i < size) { arr(i) = apply(i); i += 1 }
    arr
  }

  /**
    * Converts this chunk to a `Chunk.Booleans`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of booleans, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toBooleans[B >: O](implicit ev: B =:= Boolean): Chunk.Booleans = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Booleans => c
      case other =>
        Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Bytes`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toBytes[B >: O](implicit ev: B =:= Byte): Chunk.Bytes = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Bytes => c
      case other          => Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
    }
  }

  /** Converts this chunk to a `java.nio.ByteBuffer`. */
  def toByteBuffer[B >: O](implicit ev: B =:= Byte): JByteBuffer = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Bytes =>
        JByteBuffer.wrap(c.values, c.offset, c.length)
      case c: Chunk.ByteBuffer =>
        val b = c.buf.asReadOnlyBuffer
        if (c.offset == 0 && b.position() == 0 && c.size == b.limit()) b
        else {
          (b: JBuffer).position(c.offset.toInt)
          (b: JBuffer).limit(c.offset.toInt + c.size)
          b
        }
      case other =>
        JByteBuffer.wrap(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Shorts`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toShorts[B >: O](implicit ev: B =:= Short): Chunk.Shorts = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Shorts => c
      case other =>
        Chunk.Shorts(this.asInstanceOf[Chunk[Short]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Ints`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toInts[B >: O](implicit ev: B =:= Int): Chunk.Ints = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Ints => c
      case other         => Chunk.Ints(this.asInstanceOf[Chunk[Int]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Longs`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of longs, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toLongs[B >: O](implicit ev: B =:= Long): Chunk.Longs = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Longs => c
      case other          => Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Floats`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of doubles, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toFloats[B >: O](implicit ev: B =:= Float): Chunk.Floats = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Floats => c
      case other =>
        Chunk.Floats(this.asInstanceOf[Chunk[Float]].toArray, 0, size)
    }
  }

  /**
    * Converts this chunk to a `Chunk.Doubles`, allowing access to the underlying array of elements.
    * If this chunk is already backed by an unboxed array of doubles, this method runs in constant time.
    * Otherwise, this method will copy of the elements of this chunk in to a single array.
    */
  def toDoubles[B >: O](implicit ev: B =:= Double): Chunk.Doubles = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Doubles => c
      case other =>
        Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray, 0, size)
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
      buf.result
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
      buf.result
    }

  /**
    * Returns true if this chunk is known to have elements of type `B`.
    * This is determined by checking if the chunk type mixes in `Chunk.KnownElementType`.
    */
  def knownElementType[B](implicit classTag: ClassTag[B]): Boolean = this match {
    case ket: Chunk.KnownElementType[_] if ket.elementClassTag eq classTag => true
    case _                                                                 => false
  }

  /**
    * Zips this chunk the the supplied chunk, returning a chunk of tuples.
    */
  def zip[O2](that: Chunk[O2]): Chunk[(O, O2)] = zipWith(that)(Tuple2.apply)

  /**
    * Zips this chunk with the supplied chunk, passing each pair to `f`, resulting in
    * an output chunk.
    */
  def zipWith[O2, O3](that: Chunk[O2])(f: (O, O2) => O3): Chunk[O3] = {
    val sz = size.min(that.size)
    val b = collection.mutable.Buffer.newBuilder[O3]
    b.sizeHint(sz)
    var i = 0
    while (i < sz) {
      b += f(apply(i), that.apply(i))
      i += 1
    }
    Chunk.buffer(b.result)
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

  override def equals(a: Any): Boolean = a match {
    case c: Chunk[O] =>
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

object Chunk {

  /** Optional mix-in that provides the class tag of the element type in a chunk. */
  trait KnownElementType[A] { self: Chunk[A] =>
    def elementClassTag: ClassTag[A]
  }

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
    else if (v.size == 1)
      singleton(v.head) // Use size instead of tail.isEmpty as vectors know their size
    else new VectorChunk(v)

  private final class VectorChunk[O](v: Vector[O]) extends Chunk[O] {
    def size = v.length
    def apply(i: Int) = v(i)
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = v.copyToArray(xs, start)
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
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = s.copyToArray(xs, start)
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
  def iterable[O](i: collection.Iterable[O]): Chunk[O] = i match {
    case ArrayBackedSeq(arr) =>
      // arr is either a primitive array or a boxed array
      // cast is safe b/c the array constructor will check for primitive vs boxed arrays
      array(arr.asInstanceOf[Array[O]])
    case v: Vector[O]                    => vector(v)
    case b: collection.mutable.Buffer[O] => buffer(b)
    case l: List[O] =>
      if (l.isEmpty) empty
      else if (l.tail.isEmpty) singleton(l.head)
      else {
        val bldr = collection.mutable.Buffer.newBuilder[O]
        bldr ++= l
        buffer(bldr.result)
      }
    case ix: GIndexedSeq[O] => indexedSeq(ix)
    case _ =>
      if (i.isEmpty) empty
      else {
        val itr = i.iterator
        val head = itr.next
        if (itr.hasNext) {
          val bldr = collection.mutable.Buffer.newBuilder[O]
          bldr += head
          bldr ++= itr
          buffer(bldr.result)
        } else singleton(head)
      }
  }

  /** Creates a chunk backed by a `Chain`. */
  def chain[O](c: Chain[O]): Chunk[O] =
    seq(c.toList)

  /**
    * Creates a chunk backed by a mutable buffer. The underlying buffer must not be modified after
    * it is passed to this function.
    */
  def buffer[O](b: collection.mutable.Buffer[O]): Chunk[O] =
    if (b.isEmpty) empty
    else if (b.size == 1) singleton(b.head)
    else new BufferChunk(b)

  private final class BufferChunk[O](b: collection.mutable.Buffer[O]) extends Chunk[O] {
    def size = b.length
    def apply(i: Int) = b(i)
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = b.copyToArray(xs, start)
    override def toVector = b.toVector

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else buffer(b.drop(n))

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
  def array[O](values: Array[O]): Chunk[O] =
    values.size match {
      case 0 => empty
      case 1 => singleton(values(0))
      case n =>
        values match {
          case a: Array[Boolean] => booleans(a)
          case a: Array[Byte]    => bytes(a)
          case a: Array[Short]   => shorts(a)
          case a: Array[Int]     => ints(a)
          case a: Array[Long]    => longs(a)
          case a: Array[Float]   => floats(a)
          case a: Array[Double]  => doubles(a)
          case _                 => boxed(values)
        }
    }

  private def checkBounds(values: Array[_], offset: Int, length: Int): Unit = {
    require(offset >= 0 && offset <= values.size)
    require(length >= 0 && length <= values.size)
    val end = offset + length
    require(end >= 0 && end <= values.size)
  }

  /** Creates a chunk backed by an array. If `O` is a primitive type, elements will be boxed. */
  def boxed[O](values: Array[O]): Chunk[O] = Boxed(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array. If `A` is a primitive type, elements will be boxed. */
  def boxed[O](values: Array[O], offset: Int, length: Int): Chunk[O] =
    Boxed(values, offset, length)

  final case class Boxed[O](values: Array[O], offset: Int, length: Int) extends Chunk[O] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)

    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[AnyRef]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      Boxed(values, offset, n) -> Boxed(values, offset + n, length - n)

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Boxed(values, offset + n, length - n)

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Boxed(values, offset, n)

    override def toArray[O2 >: O: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Boxed {
    def apply[O](values: Array[O]): Boxed[O] = Boxed(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of booleans. */
  def booleans(values: Array[Boolean]): Chunk[Boolean] =
    Booleans(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of booleans. */
  def booleans(values: Array[Boolean], offset: Int, length: Int): Chunk[Boolean] =
    Booleans(values, offset, length)

  final case class Booleans(values: Array[Boolean], offset: Int, length: Int)
      extends Chunk[Boolean]
      with KnownElementType[Boolean] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Boolean
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Boolean](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Boolean]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Boolean] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Booleans(values, offset + n, length - n)

    override def take(n: Int): Chunk[Boolean] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Booleans(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Boolean], Chunk[Boolean]) =
      Booleans(values, offset, n) -> Booleans(values, offset + n, length - n)
    override def toArray[O2 >: Boolean: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Booleans {
    def apply(values: Array[Boolean]): Booleans =
      Booleans(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of bytes. */
  def bytes(values: Array[Byte]): Chunk[Byte] = Bytes(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of bytes. */
  def bytes(values: Array[Byte], offset: Int, length: Int): Chunk[Byte] =
    Bytes(values, offset, length)

  final case class Bytes(values: Array[Byte], offset: Int, length: Int)
      extends Chunk[Byte]
      with KnownElementType[Byte] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Byte
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Byte]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Byte] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Bytes(values, offset + n, length - n)

    override def take(n: Int): Chunk[Byte] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Bytes(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) =
      Bytes(values, offset, n) -> Bytes(values, offset + n, length - n)
    override def toArray[O2 >: Byte: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }

  object Bytes {
    def apply(values: Array[Byte]): Bytes = Bytes(values, 0, values.length)
  }

  sealed abstract class Buffer[A <: Buffer[A, B, C], B <: JBuffer, C: ClassTag](buf: B,
                                                                                val offset: Int,
                                                                                val size: Int)
      extends Chunk[C]
      with KnownElementType[C] {

    def elementClassTag: ClassTag[C] = implicitly[ClassTag[C]]
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
      if (xs.isInstanceOf[Array[C]]) {
        get(b, xs.asInstanceOf[Array[C]], start, size)
        ()
      } else {
        val arr = new Array[C](size)
        get(b, arr, 0, size)
        arr.copyToArray(xs, start)
      }
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

  object ShortBuffer {

    def apply(buf: JShortBuffer): ShortBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JShortBuffer): ShortBuffer =
      new ShortBuffer(buf, buf.position, buf.remaining)

  }

  final case class ShortBuffer(buf: JShortBuffer, override val offset: Int, override val size: Int)
      extends Buffer[ShortBuffer, JShortBuffer, Short](buf, offset, size) {

    def readOnly(b: JShortBuffer): JShortBuffer =
      b.asReadOnlyBuffer()

    def get(b: JShortBuffer, n: Int) =
      b.get(n)

    def buffer(b: JShortBuffer): ShortBuffer = ShortBuffer.view(b)

    override def get(b: JShortBuffer, dest: Array[Short], offset: Int, length: Int): JShortBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JShortBuffer): JShortBuffer = b.duplicate()

    // Duplicated from superclass to work around Scala.js ClassCastException when using inherited version
    override def copyToArray[O2 >: Short](xs: Array[O2], start: Int): Unit = {
      val b = readOnly(buf)
      (b: JBuffer).position(offset)
      (b: JBuffer).limit(offset + size)
      if (xs.isInstanceOf[Array[Short]]) {
        get(b, xs.asInstanceOf[Array[Short]], start, size)
        ()
      } else {
        val arr = new Array[Short](size)
        get(b, arr, 0, size)
        arr.copyToArray(xs, start)
      }
    }
  }

  /** Creates a chunk backed by an short buffer, bounded by the current position and limit */
  def shortBuffer(buf: JShortBuffer): Chunk[Short] = ShortBuffer(buf)

  object LongBuffer {
    def apply(buf: JLongBuffer): LongBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JLongBuffer): LongBuffer =
      new LongBuffer(buf, buf.position, buf.remaining)
  }

  final case class LongBuffer(buf: JLongBuffer, override val offset: Int, override val size: Int)
      extends Buffer[LongBuffer, JLongBuffer, Long](buf, offset, size) {

    def readOnly(b: JLongBuffer): JLongBuffer =
      b.asReadOnlyBuffer()

    def get(b: JLongBuffer, n: Int) =
      b.get(n)

    def buffer(b: JLongBuffer): LongBuffer = LongBuffer.view(b)

    override def get(b: JLongBuffer, dest: Array[Long], offset: Int, length: Int): JLongBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JLongBuffer): JLongBuffer = b.duplicate()
  }

  /** Creates a chunk backed by an long buffer, bounded by the current position and limit */
  def longBuffer(buf: JLongBuffer): Chunk[Long] = LongBuffer(buf)

  object DoubleBuffer {
    def apply(buf: JDoubleBuffer): DoubleBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JDoubleBuffer): DoubleBuffer =
      new DoubleBuffer(buf, buf.position, buf.remaining)
  }

  final case class DoubleBuffer(buf: JDoubleBuffer,
                                override val offset: Int,
                                override val size: Int)
      extends Buffer[DoubleBuffer, JDoubleBuffer, Double](buf, offset, size) {

    def readOnly(b: JDoubleBuffer): JDoubleBuffer =
      b.asReadOnlyBuffer()

    def get(b: JDoubleBuffer, n: Int) =
      b.get(n)

    def buffer(b: JDoubleBuffer): DoubleBuffer = DoubleBuffer.view(b)

    override def get(b: JDoubleBuffer,
                     dest: Array[Double],
                     offset: Int,
                     length: Int): JDoubleBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JDoubleBuffer): JDoubleBuffer = b.duplicate()

    // Duplicated from superclass to work around Scala.js ClassCastException when using inherited version
    override def copyToArray[O2 >: Double](xs: Array[O2], start: Int): Unit = {
      val b = readOnly(buf)
      (b: JBuffer).position(offset)
      (b: JBuffer).limit(offset + size)
      if (xs.isInstanceOf[Array[Double]]) {
        get(b, xs.asInstanceOf[Array[Double]], start, size)
        ()
      } else {
        val arr = new Array[Double](size)
        get(b, arr, 0, size)
        arr.copyToArray(xs, start)
      }
    }
  }

  /** Creates a chunk backed by an double buffer, bounded by the current position and limit */
  def doubleBuffer(buf: JDoubleBuffer): Chunk[Double] = DoubleBuffer(buf)

  object FloatBuffer {
    def apply(buf: JFloatBuffer): FloatBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JFloatBuffer): FloatBuffer =
      new FloatBuffer(buf, buf.position, buf.remaining)
  }

  final case class FloatBuffer(buf: JFloatBuffer, override val offset: Int, override val size: Int)
      extends Buffer[FloatBuffer, JFloatBuffer, Float](buf, offset, size) {

    def readOnly(b: JFloatBuffer): JFloatBuffer =
      b.asReadOnlyBuffer()

    def get(b: JFloatBuffer, n: Int) =
      b.get(n)

    def buffer(b: JFloatBuffer): FloatBuffer = FloatBuffer.view(b)

    override def get(b: JFloatBuffer, dest: Array[Float], offset: Int, length: Int): JFloatBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JFloatBuffer): JFloatBuffer = b.duplicate()
  }

  /** Creates a chunk backed by an float buffer, bounded by the current position and limit */
  def floatBuffer(buf: JFloatBuffer): Chunk[Float] = FloatBuffer(buf)

  object IntBuffer {
    def apply(buf: JIntBuffer): IntBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JIntBuffer): IntBuffer =
      new IntBuffer(buf, buf.position, buf.remaining)
  }

  final case class IntBuffer(buf: JIntBuffer, override val offset: Int, override val size: Int)
      extends Buffer[IntBuffer, JIntBuffer, Int](buf, offset, size) {

    def readOnly(b: JIntBuffer): JIntBuffer =
      b.asReadOnlyBuffer()

    def get(b: JIntBuffer, n: Int) =
      b.get(n)

    def buffer(b: JIntBuffer): IntBuffer = IntBuffer.view(b)

    override def get(b: JIntBuffer, dest: Array[Int], offset: Int, length: Int): JIntBuffer =
      b.get(dest, offset, length)

    def duplicate(b: JIntBuffer): JIntBuffer = b.duplicate()

    // Duplicated from superclass to work around Scala.js ClassCastException when using inherited version
    override def copyToArray[O2 >: Int](xs: Array[O2], start: Int): Unit = {
      val b = readOnly(buf)
      (b: JBuffer).position(offset)
      (b: JBuffer).limit(offset + size)
      if (xs.isInstanceOf[Array[Int]]) {
        get(b, xs.asInstanceOf[Array[Int]], start, size)
        ()
      } else {
        val arr = new Array[Int](size)
        get(b, arr, 0, size)
        arr.copyToArray(xs, start)
      }
    }
  }

  /** Creates a chunk backed by an int buffer, bounded by the current position and limit */
  def intBuffer(buf: JIntBuffer): Chunk[Int] = IntBuffer(buf)

  object CharBuffer {
    def apply(buf: JCharBuffer): CharBuffer =
      view(buf.duplicate().asReadOnlyBuffer)

    def view(buf: JCharBuffer): CharBuffer =
      new CharBuffer(buf, buf.position, buf.remaining)
  }

  final case class CharBuffer(buf: JCharBuffer, override val offset: Int, override val size: Int)
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

  final case class ByteBuffer private (buf: JByteBuffer,
                                       override val offset: Int,
                                       override val size: Int)
      extends Buffer[ByteBuffer, JByteBuffer, Byte](buf, offset, size) {

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

  /** Creates a chunk backed by an array of shorts. */
  def shorts(values: Array[Short]): Chunk[Short] =
    Shorts(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of shorts. */
  def shorts(values: Array[Short], offset: Int, length: Int): Chunk[Short] =
    Shorts(values, offset, length)

  final case class Shorts(values: Array[Short], offset: Int, length: Int)
      extends Chunk[Short]
      with KnownElementType[Short] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Short
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Short](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Short]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Short] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Shorts(values, offset + n, length - n)

    override def take(n: Int): Chunk[Short] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Shorts(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Short], Chunk[Short]) =
      Shorts(values, offset, n) -> Shorts(values, offset + n, length - n)
    override def toArray[O2 >: Short: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Shorts {
    def apply(values: Array[Short]): Shorts = Shorts(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of ints. */
  def ints(values: Array[Int]): Chunk[Int] = Ints(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of ints. */
  def ints(values: Array[Int], offset: Int, length: Int): Chunk[Int] =
    Ints(values, offset, length)

  final case class Ints(values: Array[Int], offset: Int, length: Int)
      extends Chunk[Int]
      with KnownElementType[Int] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Int
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Int](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Int]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Int] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Ints(values, offset + n, length - n)

    override def take(n: Int): Chunk[Int] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Ints(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Int], Chunk[Int]) =
      Ints(values, offset, n) -> Ints(values, offset + n, length - n)
    override def toArray[O2 >: Int: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Ints {
    def apply(values: Array[Int]): Ints = Ints(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of longs. */
  def longs(values: Array[Long]): Chunk[Long] = Longs(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of ints. */
  def longs(values: Array[Long], offset: Int, length: Int): Chunk[Long] =
    Longs(values, offset, length)

  final case class Longs(values: Array[Long], offset: Int, length: Int)
      extends Chunk[Long]
      with KnownElementType[Long] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Long
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Long](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Long]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Long] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Longs(values, offset + n, length - n)

    override def take(n: Int): Chunk[Long] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Longs(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Long], Chunk[Long]) =
      Longs(values, offset, n) -> Longs(values, offset + n, length - n)
    override def toArray[O2 >: Long: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Longs {
    def apply(values: Array[Long]): Longs = Longs(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of floats. */
  def floats(values: Array[Float]): Chunk[Float] =
    Floats(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of floats. */
  def floats(values: Array[Float], offset: Int, length: Int): Chunk[Float] =
    Floats(values, offset, length)

  final case class Floats(values: Array[Float], offset: Int, length: Int)
      extends Chunk[Float]
      with KnownElementType[Float] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Float
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Float](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Float]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Float] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Floats(values, offset + n, length - n)

    override def take(n: Int): Chunk[Float] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Floats(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Float], Chunk[Float]) =
      Floats(values, offset, n) -> Floats(values, offset + n, length - n)
    override def toArray[O2 >: Float: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Floats {
    def apply(values: Array[Float]): Floats = Floats(values, 0, values.length)
  }

  /** Creates a chunk backed by an array of doubles. */
  def doubles(values: Array[Double]): Chunk[Double] =
    Doubles(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of doubles. */
  def doubles(values: Array[Double], offset: Int, length: Int): Chunk[Double] =
    Doubles(values, offset, length)

  final case class Doubles(values: Array[Double], offset: Int, length: Int)
      extends Chunk[Double]
      with KnownElementType[Double] {
    checkBounds(values, offset, length)
    def elementClassTag = ClassTag.Double
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

    def copyToArray[O2 >: Double](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Double]])
        System.arraycopy(values, offset, xs, start, length)
      else
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)

    override def drop(n: Int): Chunk[Double] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else Doubles(values, offset + n, length - n)

    override def take(n: Int): Chunk[Double] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else Doubles(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[Double], Chunk[Double]) =
      Doubles(values, offset, n) -> Doubles(values, offset + n, length - n)
    override def toArray[O2 >: Double: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]
  }
  object Doubles {
    def apply(values: Array[Double]): Doubles =
      Doubles(values, 0, values.length)
  }

  /** Creates a chunk backed by a byte vector. */
  def byteVector(bv: ByteVector): Chunk[Byte] =
    ByteVectorChunk(bv)

  final case class ByteVectorChunk(toByteVector: ByteVector)
      extends Chunk[Byte]
      with Chunk.KnownElementType[Byte] {
    def elementClassTag = ClassTag.Byte

    def apply(i: Int): Byte =
      toByteVector(i)

    def size: Int =
      toByteVector.size.toInt

    def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit =
      if (xs.isInstanceOf[Array[Byte]])
        toByteVector.copyToArray(xs.asInstanceOf[Array[Byte]], start)
      else toByteVector.toIndexedSeq.copyToArray(xs)

    override def drop(n: Int): Chunk[Byte] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else ByteVectorChunk(toByteVector.drop(n))

    override def take(n: Int): Chunk[Byte] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else ByteVectorChunk(toByteVector.take(n))

    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
      val (before, after) = toByteVector.splitAt(n)
      (ByteVectorChunk(before), ByteVectorChunk(after))
    }

    override def map[O2](f: Byte => O2): Chunk[O2] =
      Chunk.indexedSeq(toByteVector.toIndexedSeq.map(f))
  }

  /** Concatenates the specified sequence of chunks in to a single chunk, avoiding boxing. */
  def concat[A](chunks: GSeq[Chunk[A]]): Chunk[A] =
    if (chunks.isEmpty) {
      Chunk.empty
    } else if (chunks.forall(c => c.knownElementType[Boolean] || c.forall(_.isInstanceOf[Boolean]))) {
      concatBooleans(chunks.asInstanceOf[GSeq[Chunk[Boolean]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Byte] || c.forall(_.isInstanceOf[Byte]))) {
      concatBytes(chunks.asInstanceOf[GSeq[Chunk[Byte]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Float] || c.forall(_.isInstanceOf[Float]))) {
      concatFloats(chunks.asInstanceOf[GSeq[Chunk[Float]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Double] || c.forall(_.isInstanceOf[Double]))) {
      concatDoubles(chunks.asInstanceOf[GSeq[Chunk[Double]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Short] || c.forall(_.isInstanceOf[Short]))) {
      concatShorts(chunks.asInstanceOf[GSeq[Chunk[Short]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Int] || c.forall(_.isInstanceOf[Int]))) {
      concatInts(chunks.asInstanceOf[GSeq[Chunk[Int]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Long] || c.forall(_.isInstanceOf[Long]))) {
      concatLongs(chunks.asInstanceOf[GSeq[Chunk[Long]]]).asInstanceOf[Chunk[A]]
    } else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val b = collection.mutable.Buffer.newBuilder[A]
      b.sizeHint(size)
      chunks.foreach(c => c.foreach(a => b += a))
      Chunk.buffer(b.result)
    }

  /** Concatenates the specified sequence of boolean chunks in to a single chunk. */
  def concatBooleans(chunks: GSeq[Chunk[Boolean]]): Chunk[Boolean] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Boolean](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.booleans(arr)
    }

  /** Concatenates the specified sequence of byte chunks in to a single chunk. */
  def concatBytes(chunks: GSeq[Chunk[Byte]]): Chunk[Byte] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Byte](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.bytes(arr)
    }

  /** Concatenates the specified sequence of float chunks in to a single chunk. */
  def concatFloats(chunks: GSeq[Chunk[Float]]): Chunk[Float] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Float](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.floats(arr)
    }

  /** Concatenates the specified sequence of double chunks in to a single chunk. */
  def concatDoubles(chunks: GSeq[Chunk[Double]]): Chunk[Double] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Double](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.doubles(arr)
    }

  /** Concatenates the specified sequence of short chunks in to a single chunk. */
  def concatShorts(chunks: GSeq[Chunk[Short]]): Chunk[Short] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Short](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.shorts(arr)
    }

  /** Concatenates the specified sequence of int chunks in to a single chunk. */
  def concatInts(chunks: GSeq[Chunk[Int]]): Chunk[Int] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Int](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.ints(arr)
    }

  /** Concatenates the specified sequence of long chunks in to a single chunk. */
  def concatLongs(chunks: GSeq[Chunk[Long]]): Chunk[Long] =
    if (chunks.isEmpty) Chunk.empty
    else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val arr = new Array[Long](size)
      var offset = 0
      chunks.foreach { c =>
        if (!c.isEmpty) {
          c.copyToArray(arr, offset)
          offset += c.size
        }
      }
      Chunk.longs(arr)
    }

  /**
    * Creates a chunk consisting of the elements of `queue`.
    */
  def queue[A](queue: collection.immutable.Queue[A]): Chunk[A] = seq(queue)

  /**
    * Creates a chunk consisting of the first `n` elements of `queue` and returns the remainder.
    */
  def queueFirstN[A](queue: collection.immutable.Queue[A],
                     n: Int): (Chunk[A], collection.immutable.Queue[A]) =
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
      (Chunk.buffer(bldr.result), cur)
    }

  implicit def fs2EqForChunk[A: Eq]: Eq[Chunk[A]] = new Eq[Chunk[A]] {
    def eqv(c1: Chunk[A], c2: Chunk[A]) =
      c1.size === c2.size && (0 until c1.size).forall(i => c1(i) === c2(i))
  }

  /**
    * `Traverse`, `Monad`, and `FunctorFilter` instance for `Chunk`.
    *
    * @example {{{
    * scala> import cats.implicits._, scala.util._
    * scala> Chunk("1", "2", "NaN").mapFilter(s => Try(s.toInt).toOption)
    * res0: fs2.Chunk[Int] = Chunk(1, 2)
    * }}}
    */
  implicit val instance: Traverse[Chunk] with Monad[Chunk] with FunctorFilter[Chunk] =
    new Traverse[Chunk] with Monad[Chunk] with FunctorFilter[Chunk] {
      def foldLeft[A, B](fa: Chunk[A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
      def foldRight[A, B](fa: Chunk[A], b: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
        def go(i: Int): Eval[B] =
          if (i < fa.size) f(fa(i), Eval.defer(go(i + 1)))
          else b
        go(0)
      }
      override def toList[A](fa: Chunk[A]): List[A] = fa.toList
      override def isEmpty[A](fa: Chunk[A]): Boolean = fa.isEmpty
      def traverse[F[_], A, B](fa: Chunk[A])(f: A => F[B])(
          implicit F: Applicative[F]): F[Chunk[B]] = {
        foldRight[A, F[Vector[B]]](fa, Eval.always(F.pure(Vector.empty))) { (a, efv) =>
          F.map2Eval(f(a), efv)(_ +: _)
        }.value
      }.map(Chunk.vector)
      def pure[A](a: A): Chunk[A] = Chunk.singleton(a)
      override def map[A, B](fa: Chunk[A])(f: A => B): Chunk[B] = fa.map(f)
      def flatMap[A, B](fa: Chunk[A])(f: A => Chunk[B]): Chunk[B] = fa.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Chunk[Either[A, B]]): Chunk[B] = {
        // Based on the implementation of tailRecM for Vector from cats, licensed under MIT
        val buf = collection.mutable.Buffer.newBuilder[B]
        var state = List(f(a).iterator)
        @tailrec
        def go(): Unit = state match {
          case Nil => ()
          case h :: tail if h.isEmpty =>
            state = tail
            go()
          case h :: tail =>
            h.next match {
              case Right(b) =>
                buf += b
                go()
              case Left(a) =>
                state = (f(a).iterator) :: h :: tail
                go()
            }
        }
        go()
        Chunk.buffer(buf.result)
      }
      override def functor: Functor[Chunk] = this
      override def mapFilter[A, B](fa: Chunk[A])(f: A => Option[B]): Chunk[B] = {
        val size = fa.size
        val b = collection.mutable.Buffer.newBuilder[B]
        b.sizeHint(size)
        var i = 0
        while (i < size) {
          val o = f(fa(i))
          if (o.isDefined)
            b += o.get
          i += 1
        }
        Chunk.buffer(b.result)
      }
    }

  /**
    * A FIFO queue of chunks that provides an O(1) size method and provides the ability to
    * take and drop individual elements while preserving the chunk structure as much as possible.
    *
    * This is similar to a queue of individual elements but chunk structure is maintained.
    */
  final class Queue[A] private (val chunks: SQueue[Chunk[A]], val size: Int) {
    def iterator: Iterator[A] = chunks.iterator.flatMap(_.iterator)

    /** Prepends a chunk to the start of this chunk queue. */
    def +:(c: Chunk[A]): Queue[A] = new Queue(c +: chunks, c.size + size)

    /** Appends a chunk to the end of this chunk queue. */
    def :+(c: Chunk[A]): Queue[A] = new Queue(chunks :+ c, size + c.size)

    /** Takes the first `n` elements of this chunk queue in a way that preserves chunk structure. */
    def take(n: Int): Queue[A] =
      if (n <= 0) Queue.empty
      else if (n >= size) this
      else {
        @tailrec
        def go(acc: SQueue[Chunk[A]], rem: SQueue[Chunk[A]], toTake: Int): Queue[A] =
          if (toTake <= 0) new Queue(acc, n)
          else {
            val (next, tail) = rem.dequeue
            val nextSize = next.size
            if (nextSize < toTake) go(acc :+ next, tail, toTake - nextSize)
            else if (nextSize == toTake) new Queue(acc :+ next, n)
            else new Queue(acc :+ next.take(toTake), n)
          }
        go(SQueue.empty, chunks, n)
      }

    /** Takes the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
    def takeRight(n: Int): Queue[A] = if (n <= 0) Queue.empty else drop(size - n)

    /** Drops the first `n` elements of this chunk queue in a way that preserves chunk structure. */
    def drop(n: Int): Queue[A] =
      if (n <= 0) this
      else if (n >= size) Queue.empty
      else {
        @tailrec
        def go(rem: SQueue[Chunk[A]], toDrop: Int): Queue[A] =
          if (toDrop <= 0) new Queue(rem, size - n)
          else {
            val next = rem.head
            val nextSize = next.size
            if (nextSize < toDrop) go(rem.tail, toDrop - nextSize)
            else if (nextSize == toDrop) new Queue(rem.tail, size - n)
            else new Queue(next.drop(toDrop) +: rem.tail, size - n)
          }
        go(chunks, n)
      }

    /** Drops the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
    def dropRight(n: Int): Queue[A] = if (n <= 0) this else take(size - n)

    /** Converts this chunk queue to a single chunk, copying all chunks to a single chunk. */
    def toChunk: Chunk[A] = Chunk.concat(chunks)

    override def equals(that: Any): Boolean = that match {
      case that: Queue[A] => size == that.size && chunks == that.chunks
      case _              => false
    }

    override def hashCode: Int = chunks.hashCode

    override def toString: String = chunks.mkString("Queue(", ", ", ")")
  }

  object Queue {
    private val empty_ = new Queue(collection.immutable.Queue.empty, 0)
    def empty[A]: Queue[A] = empty_.asInstanceOf[Queue[A]]
    def apply[A](chunks: Chunk[A]*): Queue[A] = chunks.foldLeft(empty[A])(_ :+ _)
  }

}
