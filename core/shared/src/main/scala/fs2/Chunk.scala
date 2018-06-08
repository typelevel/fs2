package fs2

import scala.collection.immutable.VectorBuilder
import scala.reflect.ClassTag
import java.nio.{ByteBuffer => JByteBuffer}

import cats.{Applicative, Eq, Eval, Foldable, Monad, Traverse}
import cats.implicits._

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
  * intermediate chunks being created (1 per call to `map`). In contrast, a chunk can be lifted to a segment
  * (via `toSegment`) to get arbitrary operator fusion.
  */
abstract class Chunk[+O] extends Serializable {

  /** Returns the number of elements in this chunk. */
  def size: Int

  /** Returns the element at the specified index. Throws if index is < 0 or >= size. */
  def apply(i: Int): O

  /** True if size is zero, false otherwise. */
  final def isEmpty: Boolean = size == 0

  /** False if size is zero, true otherwise. */
  final def nonEmpty: Boolean = size > 0

  /** Drops the first `n` elements of this chunk. */
  def drop(n: Int): Chunk[O] = splitAt(n)._2

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

  /** Gets the first element of this chunk. */
  def head: Option[O] = if (isEmpty) None else Some(apply(0))

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
    val b = new VectorBuilder[O2]
    b.sizeHint(size)
    for (i <- 0 until size) b += f(apply(i))
    Chunk.indexedSeq(b.result)
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

  def toByteBuffer[B >: O](implicit ev: B =:= Byte): JByteBuffer = {
    val _ = ev // Convince scalac that ev is used
    this match {
      case c: Chunk.Bytes =>
        JByteBuffer.wrap(c.values, c.offset, c.length)
      case c: Chunk.ByteBuffer =>
        val b = c.buf.asReadOnlyBuffer
        if (c.offset == 0 && b.position == 0 && c.size == b.limit) b
        else {
          b.position(c.offset.toInt)
          b.limit(c.offset.toInt + c.size)
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

  /** Invokes the supplied function for each element of this chunk. */
  def foreach(f: O => Unit): Unit = {
    var i = 0
    while (i < size) {
      f(apply(i))
      i += 1
    }
  }

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

  /** Converts this chunk to a segment. */
  def toSegment: Segment[O, Unit] = Segment.chunk(this)

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

  override def hashCode: Int = toVector.hashCode

  override def equals(a: Any): Boolean = a match {
    case c: Chunk[O] => toVector == c.toVector
    case _           => false
  }

  override def toString = {
    val vs = (0 until size).view.map(i => apply(i)).mkString(", ")
    s"Chunk($vs)"
  }
}

object Chunk {

  private val empty_ : Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = sys.error(s"Chunk.empty.apply($i)")
    protected def splitAtChunk_(n: Int): (Chunk[Nothing], Chunk[Nothing]) =
      sys.error("impossible")
    override def map[O2](f: Nothing => O2): Chunk[O2] = empty
    override def toString = "empty"
  }

  /** Chunk with no elements. */
  def empty[A]: Chunk[A] = empty_

  /** Creates a chunk consisting of a single element. */
  def singleton[O](o: O): Chunk[O] = new Chunk[O] {
    def size = 1
    def apply(i: Int) =
      if (i == 0) o else throw new IndexOutOfBoundsException()
    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      sys.error("impossible")
    override def map[O2](f: O => O2): Chunk[O2] = singleton(f(o))
  }

  /** Creates a chunk backed by a vector. */
  def vector[O](v: Vector[O]): Chunk[O] =
    if (v.isEmpty) empty
    else
      new Chunk[O] {
        def size = v.length
        def apply(i: Int) = v(i)
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
  def indexedSeq[O](s: IndexedSeq[O]): Chunk[O] =
    if (s.isEmpty) empty
    else
      new Chunk[O] {
        def size = s.length
        def apply(i: Int) = s(i)
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

  /** Creates a chunk backed by a `Seq`. */
  def seq[O](s: Seq[O]): Chunk[O] = s match {
    case a: collection.mutable.WrappedArray[O] => array(a.array)
    case v: Vector[O]                          => vector(v)
    case ix: IndexedSeq[O]                     => indexedSeq(ix)
    case _                                     => buffer(collection.mutable.Buffer(s: _*))
  }

  /**
    * Creates a chunk backed by a mutable buffer. The underlying buffer must not be modified after
    * it is passed to this function.
    */
  def buffer[O](b: collection.mutable.Buffer[O]): Chunk[O] =
    if (b.isEmpty) empty
    else
      new Chunk[O] {
        def size = b.length
        def apply(i: Int) = b(i)
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
  def array[O](values: Array[O]): Chunk[O] = values match {
    case a: Array[Boolean] => booleans(a)
    case a: Array[Byte]    => bytes(a)
    case a: Array[Short]   => shorts(a)
    case a: Array[Int]     => ints(a)
    case a: Array[Long]    => longs(a)
    case a: Array[Float]   => floats(a)
    case a: Array[Double]  => doubles(a)
    case _                 => boxed(values)
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
      extends Chunk[Boolean] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  final case class Bytes(values: Array[Byte], offset: Int, length: Int) extends Chunk[Byte] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  /** Creates a chunk backed by an byte buffer, bounded by the current position and limit */
  def byteBuffer(buf: JByteBuffer): Chunk[Byte] = ByteBuffer(buf)

  final case class ByteBuffer private (buf: JByteBuffer, offset: Int, size: Int)
      extends Chunk[Byte] {
    def apply(i: Int): Byte = buf.get(i + offset)

    override def drop(n: Int): Chunk[Byte] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else {
        val second = buf.asReadOnlyBuffer
        second.position(n + offset)
        ByteBuffer(second)
      }

    override def take(n: Int): Chunk[Byte] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else {
        val first = buf.asReadOnlyBuffer
        first.limit(n + offset)
        ByteBuffer(first)
      }

    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
      val first = buf.asReadOnlyBuffer
      first.limit(n + offset)
      val second = buf.asReadOnlyBuffer
      second.position(n + offset)
      (ByteBuffer(first), ByteBuffer(second))
    }
    override def toArray[O2 >: Byte: ClassTag]: Array[O2] = {
      val bs = new Array[Byte](size)
      val b = buf.duplicate
      b.position(offset)
      b.get(bs, 0, size)
      bs.asInstanceOf[Array[O2]]
    }
  }
  object ByteBuffer {
    def apply(buf: JByteBuffer): ByteBuffer =
      ByteBuffer(buf, buf.position, buf.remaining)
  }

  /** Creates a chunk backed by an array of shorts. */
  def shorts(values: Array[Short]): Chunk[Short] =
    Shorts(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of shorts. */
  def shorts(values: Array[Short], offset: Int, length: Int): Chunk[Short] =
    Shorts(values, offset, length)

  final case class Shorts(values: Array[Short], offset: Int, length: Int) extends Chunk[Short] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  final case class Ints(values: Array[Int], offset: Int, length: Int) extends Chunk[Int] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  final case class Longs(values: Array[Long], offset: Int, length: Int) extends Chunk[Long] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  final case class Floats(values: Array[Float], offset: Int, length: Int) extends Chunk[Float] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  final case class Doubles(values: Array[Double], offset: Int, length: Int) extends Chunk[Double] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)

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

  implicit def fs2EqForChunk[A: Eq]: Eq[Chunk[A]] = new Eq[Chunk[A]] {
    def eqv(c1: Chunk[A], c2: Chunk[A]) =
      c1.size === c2.size && (0 until c1.size).forall(i => c1(i) === c2(i))
  }

  implicit val instance: Traverse[Chunk] with Monad[Chunk] = new Traverse[Chunk] with Monad[Chunk] {
    def foldLeft[A, B](fa: Chunk[A], b: B)(f: (B, A) => B): B =
      fa.foldLeft(b)(f)
    def foldRight[A, B](fa: Chunk[A], b: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      Foldable[Vector].foldRight(fa.toVector, b)(f)
    override def toList[A](fa: Chunk[A]): List[A] = fa.toList
    override def isEmpty[A](fa: Chunk[A]): Boolean = fa.isEmpty
    def traverse[F[_], A, B](fa: Chunk[A])(f: A => F[B])(implicit G: Applicative[F]): F[Chunk[B]] =
      G.map(Traverse[Vector].traverse(fa.toVector)(f))(Chunk.vector)
    def pure[A](a: A): Chunk[A] = Chunk.singleton(a)
    override def map[A, B](fa: Chunk[A])(f: A => B): Chunk[B] = fa.map(f)
    def flatMap[A, B](fa: Chunk[A])(f: A => Chunk[B]): Chunk[B] =
      fa.toSegment.flatMap(f.andThen(_.toSegment)).force.toChunk
    def tailRecM[A, B](a: A)(f: A => Chunk[Either[A, B]]): Chunk[B] =
      Chunk.seq(Monad[Vector].tailRecM(a)(f.andThen(_.toVector)))
  }
}
