package fs2

import scala.reflect.ClassTag
import java.nio.{ByteBuffer => JByteBuffer}

import cats.{Applicative, Eq, Eval, Monad, Traverse}
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

  /** Returns a chunk that has only the elements that satifsy the supplied predicate. */
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

  /** Returns the first element for which the predicate returns true or `None` if no elements satifsy the predicate. */
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

  private[fs2] final def foldRightLazy[B](z: B)(f: (O, => B) => B): B = {
    val sz = size
    def loop(idx: Int): B =
      if (idx < sz) f(apply(idx), loop(idx + 1))
      else z
    loop(0)
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

  /** Optional mix-in that provides the class tag of the element type in a chunk. */
  trait KnownElementType[A] { self: Chunk[A] =>
    def elementClassTag: ClassTag[A]
  }

  private val empty_ : Chunk[Nothing] = new Chunk[Nothing] {
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
  def singleton[O](o: O): Chunk[O] = new Chunk[O] {
    def size = 1
    def apply(i: Int) =
      if (i == 0) o else throw new IndexOutOfBoundsException()
    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit = xs(start) = o
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
  def indexedSeq[O](s: IndexedSeq[O]): Chunk[O] =
    if (s.isEmpty) empty
    else
      new Chunk[O] {
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

  /** Creates a chunk backed by an byte buffer, bounded by the current position and limit. */
  def byteBuffer(buf: JByteBuffer): Chunk[Byte] = ByteBuffer(buf)

  final case class ByteBuffer private (buf: JByteBuffer, offset: Int, size: Int)
      extends Chunk[Byte]
      with KnownElementType[Byte] {
    def elementClassTag = ClassTag.Byte

    def apply(i: Int): Byte = buf.get(i + offset)

    def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit = {
      val b = buf.asReadOnlyBuffer
      b.position(offset)
      b.limit(offset + size)
      if (xs.isInstanceOf[Array[Byte]]) {
        b.get(xs.asInstanceOf[Array[Byte]], start, size)
        ()
      } else {
        val arr = new Array[Byte](size)
        b.get(arr)
        arr.copyToArray(xs, start)
      }
    }

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

  /** Concatenates the specified sequence of chunks in to a single chunk, avoiding boxing. */
  def concat[A](chunks: Seq[Chunk[A]]): Chunk[A] =
    if (chunks.isEmpty) {
      Chunk.empty
    } else if (chunks.forall(c => c.knownElementType[Boolean] || c.forall(_.isInstanceOf[Boolean]))) {
      concatBooleans(chunks.asInstanceOf[Seq[Chunk[Boolean]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Byte] || c.forall(_.isInstanceOf[Byte]))) {
      concatBytes(chunks.asInstanceOf[Seq[Chunk[Byte]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Float] || c.forall(_.isInstanceOf[Float]))) {
      concatFloats(chunks.asInstanceOf[Seq[Chunk[Float]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Double] || c.forall(_.isInstanceOf[Double]))) {
      concatDoubles(chunks.asInstanceOf[Seq[Chunk[Double]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Short] || c.forall(_.isInstanceOf[Short]))) {
      concatShorts(chunks.asInstanceOf[Seq[Chunk[Short]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Int] || c.forall(_.isInstanceOf[Int]))) {
      concatInts(chunks.asInstanceOf[Seq[Chunk[Int]]]).asInstanceOf[Chunk[A]]
    } else if (chunks.forall(c => c.knownElementType[Long] || c.forall(_.isInstanceOf[Long]))) {
      concatLongs(chunks.asInstanceOf[Seq[Chunk[Long]]]).asInstanceOf[Chunk[A]]
    } else {
      val size = chunks.foldLeft(0)(_ + _.size)
      val b = collection.mutable.Buffer.newBuilder[A]
      b.sizeHint(size)
      chunks.foreach(c => c.foreach(a => b += a))
      Chunk.buffer(b.result)
    }

  /** Concatenates the specified sequence of boolean chunks in to a single chunk. */
  def concatBooleans(chunks: Seq[Chunk[Boolean]]): Chunk[Boolean] =
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
  def concatBytes(chunks: Seq[Chunk[Byte]]): Chunk[Byte] =
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
  def concatFloats(chunks: Seq[Chunk[Float]]): Chunk[Float] =
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
  def concatDoubles(chunks: Seq[Chunk[Double]]): Chunk[Double] =
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
  def concatShorts(chunks: Seq[Chunk[Short]]): Chunk[Short] =
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
  def concatInts(chunks: Seq[Chunk[Int]]): Chunk[Int] =
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
  def concatLongs(chunks: Seq[Chunk[Long]]): Chunk[Long] =
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

  implicit def fs2EqForChunk[A: Eq]: Eq[Chunk[A]] = new Eq[Chunk[A]] {
    def eqv(c1: Chunk[A], c2: Chunk[A]) =
      c1.size === c2.size && (0 until c1.size).forall(i => c1(i) === c2(i))
  }

  implicit val instance: Traverse[Chunk] with Monad[Chunk] = new Traverse[Chunk] with Monad[Chunk] {
    def foldLeft[A, B](fa: Chunk[A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
    def foldRight[A, B](fa: Chunk[A], b: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
      def loop(i: Int): Eval[B] =
        if (i < fa.size) f(fa(i), Eval.defer(loop(i + 1)))
        else b
      loop(0)
    }
    override def toList[A](fa: Chunk[A]): List[A] = fa.toList
    override def isEmpty[A](fa: Chunk[A]): Boolean = fa.isEmpty
    def traverse[F[_], A, B](fa: Chunk[A])(f: A => F[B])(implicit F: Applicative[F]): F[Chunk[B]] = {
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
      @annotation.tailrec
      def loop(): Unit = state match {
        case Nil => ()
        case h :: tail if h.isEmpty =>
          state = tail
          loop()
        case h :: tail =>
          h.next match {
            case Right(b) =>
              buf += b
              loop()
            case Left(a) =>
              state = (f(a).iterator) :: h :: tail
              loop()
          }
      }
      loop()
      Chunk.buffer(buf.result)
    }
  }
}
