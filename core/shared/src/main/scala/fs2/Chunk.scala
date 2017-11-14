package fs2

import cats.Eval
import scala.reflect.ClassTag

/**
 * Segment with a known size and that allows index-based random access of elements.
 *
 * `Chunk`s can be created for a variety of collection types using methods on the `Chunk` companion
 * (e.g., `Chunk.vector`, `Chunk.seq`, `Chunk.array`). Additionally, the `Chunk` companion
 * defines a subtype of `Chunk` for each primitive type, using an unboxed primitive array.
 * To work with unboxed arrays, use methods like `toBytes` to convert a `Chunk[Byte]` to a `Chunk.Bytes`
 * and then access the array directly.
 *
 * This type intentionally has a very limited API. Most operations are defined on `Segment` in a lazy/fusable
 * fashion. In general, better performance comes from fusing as many operations as possible. As such, the
 * chunk API is minimal, to encourage use of the fusable operations.
 *
 * Some operations have a lazy/fusable definition (on `Segment`) and a strict definition
 * on `Chunk`. To call such operations, use the `.strict` method -- e.g., `c.strict.splitAt(3)`.
 */
abstract class Chunk[+O] extends Segment[O,Unit] { self =>

  private[fs2]
  def stage0(depth: Segment.Depth, defer: Segment.Defer, emit: O => Unit, emits: Chunk[O] => Unit, done: Unit => Unit) = {
    var emitted = false
    Eval.now {
      Segment.step(if (emitted) Segment.empty else this) {
        if (!emitted) {
          emitted = true
          emits(this)
        }
        done(())
      }
    }
  }

  /** Returns the number of elements in this chunk. */
  def size: Int

  /** Returns the element at the specified index. Throws if index is < 0 or >= size. */
  def apply(i: Int): O

  /** True if size is zero, false otherwise. */
  final def isEmpty: Boolean = size == 0

  /** False if size is zero, true otherwise. */
  final def nonEmpty: Boolean = size > 0

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

  /** Copies the elements of this chunk to an array. */
  override def toArray[O2 >: O: ClassTag]: Array[O2] = {
    val arr = new Array[O2](size)
    var i = 0
    this.map { b => arr(i) = b; i += 1 }.drain.run
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
      case other => Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray, 0, size)
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
      case other => Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
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
      case other => Chunk.Shorts(this.asInstanceOf[Chunk[Short]].toArray, 0, size)
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
      case other => Chunk.Ints(this.asInstanceOf[Chunk[Int]].toArray, 0, size)
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
      case other => Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray, 0, size)
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
      case other => Chunk.Floats(this.asInstanceOf[Chunk[Float]].toArray, 0, size)
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
      case other => Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray, 0, size)
    }
  }

  override def unconsChunk: Either[Unit, (Chunk[O],Segment[O,Unit])] = Right(this -> Chunk.empty)
  override def foreachChunk(f: Chunk[O] => Unit): Unit = f(this)
  override def toChunk: Chunk[O] = this
  override def toChunks: Catenable[Chunk[O]] = Catenable.singleton(this)
  override def toVector: Vector[O] = {
    val buf = new collection.immutable.VectorBuilder[O]
    var i = 0
    while (i < size) {
      buf += apply(i)
      i += 1
    }
    buf.result
  }

  /** Strict version of `splitAt` - `n` is guaranteed to be within bounds so implementations do not need to do bounds checking. */
  protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O])

  /** Strict version of `map`. */
  protected def mapStrict[O2](f: O => O2): Chunk[O2]

  /** Provides access to strict equivalent methods defined lazily on `Segment`. */
  final def strict: Chunk.StrictOps[O] = new Chunk.StrictOps(this)

  override def toString = {
    val vs = (0 until size).view.map(i => apply(i)).mkString(", ")
    s"Chunk($vs)"
  }
}

object Chunk {

  private val empty_ : Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = sys.error(s"Chunk.empty.apply($i)")
    override def stage0(depth: Segment.Depth, defer: Segment.Defer, emit: Nothing => Unit, emits: Chunk[Nothing] => Unit, done: Unit => Unit) =
      Eval.now(Segment.step(empty_)(done(())))
    override def unconsChunk: Either[Unit, (Chunk[Nothing],Segment[Nothing,Unit])] = Left(())
    override def foreachChunk(f: Chunk[Nothing] => Unit): Unit = ()
    override def toVector: Vector[Nothing] = Vector.empty
    protected def splitAtChunk_(n: Int): (Chunk[Nothing], Chunk[Nothing]) = sys.error("impossible")
    protected def mapStrict[O2](f: Nothing => O2): Chunk[O2] = empty
    override def toString = "empty"
  }

  /** Chunk with no elements. */
  def empty[A]: Chunk[A] = empty_

  /** Creates a chunk consisting of a single element. */
  def singleton[O](o: O): Chunk[O] = new Chunk[O] {
    def size = 1
    def apply(i: Int) = { if (i == 0) o else throw new IndexOutOfBoundsException() }
    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = sys.error("impossible")
    protected def mapStrict[O2](f: O => O2): Chunk[O2] = singleton(f(o))
  }

  /** Creates a chunk backed by a vector. */
  def vector[O](v: Vector[O]): Chunk[O] = {
    if (v.isEmpty) empty
    else new Chunk[O] {
      def size = v.length
      def apply(i: Int) = v(i)
      override def toVector = v
      protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
        val (fst,snd) = v.splitAt(n)
        vector(fst) -> vector(snd)
      }
      protected def mapStrict[O2](f: O => O2): Chunk[O2] = vector(v.map(f))
    }
  }

  /** Creates a chunk backed by an `IndexedSeq`. */
  def indexedSeq[O](s: IndexedSeq[O]): Chunk[O] = {
    if (s.isEmpty) empty
    else new Chunk[O] {
      def size = s.length
      def apply(i: Int) = s(i)
      override def toVector = s.toVector
      protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) = {
        val (fst,snd) = s.splitAt(n)
        indexedSeq(fst) -> indexedSeq(snd)
      }
      protected def mapStrict[O2](f: O => O2): Chunk[O2] = indexedSeq(s.map(f))
    }
  }

  /** Creates a chunk backed by a `Seq`. */
  def seq[O](s: Seq[O]): Chunk[O] =
    if (s.isEmpty) empty else indexedSeq(s.toIndexedSeq)

  /** Creates a chunk with the specified values. */
  def apply[O](os: O*): Chunk[O] = seq(os)

  /** Creates a chunk backed by an array. */
  def array[O](values: Array[O]): Chunk[O] = values match {
    case a: Array[Boolean] => booleans(a)
    case a: Array[Byte] => bytes(a)
    case a: Array[Short] => shorts(a)
    case a: Array[Int] => ints(a)
    case a: Array[Long] => longs(a)
    case a: Array[Float] => floats(a)
    case a: Array[Double] => doubles(a)
    case _ => boxed(values)
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
  def boxed[O](values: Array[O], offset: Int, length: Int): Chunk[O] = Boxed(values, offset, length)

  final case class Boxed[O](values: Array[O], offset: Int, length: Int) extends Chunk[O] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      Boxed(values, offset, n) -> Boxed(values, offset + n, length - n)
    protected def mapStrict[O2](f: O => O2): Chunk[O2] = seq(values.map(f))
  }
  object Boxed { def apply[O](values: Array[O]): Boxed[O] = Boxed(values, 0, values.length) }

  /** Creates a chunk backed by an array of booleans. */
  def booleans(values: Array[Boolean]): Chunk[Boolean] = Booleans(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of booleans. */
  def booleans(values: Array[Boolean], offset: Int, length: Int): Chunk[Boolean] = Booleans(values, offset, length)

  final case class Booleans(values: Array[Boolean], offset: Int, length: Int) extends Chunk[Boolean] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Boolean], Chunk[Boolean]) =
      Booleans(values, offset, n) -> Booleans(values, offset + n, length - n)
    protected def mapStrict[O2](f: Boolean => O2): Chunk[O2] = seq(values.map(f))
  }
  object Booleans { def apply(values: Array[Boolean]): Booleans = Booleans(values, 0, values.length) }

  /** Creates a chunk backed by an array of bytes. */
  def bytes(values: Array[Byte]): Chunk[Byte] = Bytes(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of bytes. */
  def bytes(values: Array[Byte], offset: Int, length: Int): Chunk[Byte] = Bytes(values, offset, length)

  final case class Bytes(values: Array[Byte], offset: Int, length: Int) extends Chunk[Byte] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) =
      Bytes(values, offset, n) -> Bytes(values, offset + n, length - n)
    protected def mapStrict[O2](f: Byte => O2): Chunk[O2] = seq(values.map(f))
  }
  object Bytes { def apply(values: Array[Byte]): Bytes = Bytes(values, 0, values.length) }

  /** Creates a chunk backed by an array of shorts. */
  def shorts(values: Array[Short]): Chunk[Short] = Shorts(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of shorts. */
  def shorts(values: Array[Short], offset: Int, length: Int): Chunk[Short] = Shorts(values, offset, length)

  final case class Shorts(values: Array[Short], offset: Int, length: Int) extends Chunk[Short] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Short], Chunk[Short]) =
      Shorts(values, offset, n) -> Shorts(values, offset + n, length - n)
    protected def mapStrict[O2](f: Short => O2): Chunk[O2] = seq(values.map(f))
  }
  object Shorts { def apply(values: Array[Short]): Shorts = Shorts(values, 0, values.length) }

  /** Creates a chunk backed by an array of ints. */
  def ints(values: Array[Int]): Chunk[Int] = Ints(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of ints. */
  def ints(values: Array[Int], offset: Int, length: Int): Chunk[Int] = Ints(values, offset, length)

  final case class Ints(values: Array[Int], offset: Int, length: Int) extends Chunk[Int] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Int], Chunk[Int]) =
      Ints(values, offset, n) -> Ints(values, offset + n, length - n)
    protected def mapStrict[O2](f: Int => O2): Chunk[O2] = seq(values.map(f))
  }
  object Ints { def apply(values: Array[Int]): Ints = Ints(values, 0, values.length) }

  /** Creates a chunk backed by an array of longs. */
  def longs(values: Array[Long]): Chunk[Long] = Longs(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of ints. */
  def longs(values: Array[Long], offset: Int, length: Int): Chunk[Long] = Longs(values, offset, length)

  final case class Longs(values: Array[Long], offset: Int, length: Int) extends Chunk[Long] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Long], Chunk[Long]) =
      Longs(values, offset, n) -> Longs(values, offset + n, length - n)
    protected def mapStrict[O2](f: Long => O2): Chunk[O2] = seq(values.map(f))
  }
  object Longs { def apply(values: Array[Long]): Longs = Longs(values, 0, values.length) }

  /** Creates a chunk backed by an array of floats. */
  def floats(values: Array[Float]): Chunk[Float] = Floats(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of floats. */
  def floats(values: Array[Float], offset: Int, length: Int): Chunk[Float] = Floats(values, offset, length)

  final case class Floats(values: Array[Float], offset: Int, length: Int) extends Chunk[Float] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Float], Chunk[Float]) =
      Floats(values, offset, n) -> Floats(values, offset + n, length - n)
    protected def mapStrict[O2](f: Float => O2): Chunk[O2] = seq(values.map(f))
  }
  object Floats { def apply(values: Array[Float]): Floats = Floats(values, 0, values.length) }

  /** Creates a chunk backed by an array of doubles. */
  def doubles(values: Array[Double]): Chunk[Double] = Doubles(values, 0, values.length)

  /** Creates a chunk backed by a subsequence of an array of doubles. */
  def doubles(values: Array[Double], offset: Int, length: Int): Chunk[Double] = Doubles(values, offset, length)

  final case class Doubles(values: Array[Double], offset: Int, length: Int) extends Chunk[Double] {
    checkBounds(values, offset, length)
    def size = length
    def apply(i: Int) = values(offset + i)
    def at(i: Int) = values(offset + i)
    protected def splitAtChunk_(n: Int): (Chunk[Double], Chunk[Double]) =
      Doubles(values, offset, n) -> Doubles(values, offset + n, length - n)
    protected def mapStrict[O2](f: Double => O2): Chunk[O2] = seq(values.map(f))
  }
  object Doubles { def apply(values: Array[Double]): Doubles = Doubles(values, 0, values.length) }

  /**
   * Defines operations on a `Chunk` that return a `Chunk` and that might otherwise conflict
   * with lazy implementations defined on `Segment`.
   */
  final class StrictOps[+O](private val self: Chunk[O]) extends AnyVal {

    /** Gets the first element of this chunk or throws if the chunk is empty. */
    def head: O = self(0)

    /** Gets the last element of this chunk or throws if the chunk is empty. */
    def last: O = self(self.size - 1)

    /** Splits this chunk in to two chunks at the specified index. */
    def splitAt(n: Int): (Chunk[O], Chunk[O]) = {
      if (n <= 0) (Chunk.empty, self)
      else if (n >= self.size) (self, Chunk.empty)
      else self.splitAtChunk_(n)
    }

    /** Takes the first `n` elements of this chunk. */
    def take(n: Int): Chunk[O] = splitAt(n)._1

    /** Drops the first `n` elements of this chunk. */
    def drop(n: Int): Chunk[O] = splitAt(n)._2

    /** Strict version of `map`. */
    def map[O2](f: O => O2): Chunk[O2] = self.mapStrict(f)
  }
}
