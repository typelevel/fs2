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
  def stage0 = (_, _, emit, emits, done) => Eval.now {
    var emitted = false
    Segment.step(if (emitted) Segment.empty else this) {
      if (!emitted) {
        emits(this)
        emitted = true
      }
      else done(())
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
  def toArray[B >: O: ClassTag]: Array[B] = {
    val arr = new Array[B](size)
    var i = 0
    this.map { b => arr(i) = b; i += 1 }.run
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
      case other => Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray)
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
      case other => Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray)
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
      case other => Chunk.Shorts(this.asInstanceOf[Chunk[Short]].toArray)
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
      case other => Chunk.Ints(this.asInstanceOf[Chunk[Int]].toArray)
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
      case other => Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray)
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
      case other => Chunk.Floats(this.asInstanceOf[Chunk[Float]].toArray)
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
      case other => Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray)
    }
  }

  override def unconsChunk: Either[Unit, (Chunk[O],Segment[O,Unit])] = Right(this -> Chunk.empty)
  override def foreachChunk(f: Chunk[O] => Unit): Unit = f(this)
  override def toChunk: Chunk[O] = this
  override def toChunks: Catenable[Chunk[O]] = Catenable.single(this)
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
    override def stage0 = (_,_,_,_,done) => Eval.now(Segment.step(empty_)(done(())))
    override def unconsChunk: Either[Unit, (Chunk[Nothing],Segment[Nothing,Unit])] = Left(())
    override def foreachChunk(f: Chunk[Nothing] => Unit): Unit = ()
    override def toVector: Vector[Nothing] = Vector.empty
    protected def splitAtChunk_(n: Int): (Chunk[Nothing], Chunk[Nothing]) = sys.error("impossible")
    override def toString = "empty"
  }

  /** Chunk with no elements. */
  def empty[A]: Chunk[A] = empty_

  /** Creates a chunk consisting of a single element. */
  def singleton[A](a: A): Chunk[A] = new Chunk[A] {
    def size = 1
    def apply(i: Int) = { if (i == 0) a else throw new IndexOutOfBoundsException() }
    protected def splitAtChunk_(n: Int): (Chunk[A], Chunk[A]) = sys.error("impossible")
  }

  /** Creates a chunk backed by a vector. */
  def vector[A](a: Vector[A]): Chunk[A] = {
    if (a.isEmpty) empty
    else new Chunk[A] {
      def size = a.length
      def apply(i: Int) = a(i)
      override def toVector = a
      protected def splitAtChunk_(n: Int): (Chunk[A], Chunk[A]) = {
        val (fst,snd) = a.splitAt(n)
        vector(fst) -> vector(snd)
      }
    }
  }

  /** Creates a chunk backed by an `IndexedSeq`. */
  def indexedSeq[A](a: IndexedSeq[A]): Chunk[A] = {
    if (a.isEmpty) empty
    else new Chunk[A] {
      def size = a.length
      def apply(i: Int) = a(i)
      override def toVector = a.toVector
      protected def splitAtChunk_(n: Int): (Chunk[A], Chunk[A]) = {
        val (fst,snd) = a.splitAt(n)
        indexedSeq(fst) -> indexedSeq(snd)
      }
    }
  }

  /** Creates a chunk backed by a `Seq`. */
  def seq[A](a: Seq[A]): Chunk[A] =
    if (a.isEmpty) empty else indexedSeq(a.toIndexedSeq)

  /** Creates a chunk with the specified values. */
  def apply[A](as: A*): Chunk[A] = seq(as)

  /** Creates a chunk backed by an array. */
  def array[A](values: Array[A]): Chunk[A] = values match {
    case a: Array[Boolean] => booleans(a)
    case a: Array[Byte] => bytes(a)
    case a: Array[Short] => shorts(a)
    case a: Array[Int] => ints(a)
    case a: Array[Long] => longs(a)
    case a: Array[Float] => floats(a)
    case a: Array[Double] => doubles(a)
    case _ => boxed(values)
  }

  /** Creates a chunk backed by an array. If `A` is a primitive type, elements will be boxed. */
  def boxed[A](values: Array[A]): Chunk[A] = Boxed(values)
  final case class Boxed[A](values: Array[A]) extends Chunk[A] {
    def size = values.length
    def apply(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[A], Chunk[A]) = {
      val (fst,snd) = values.splitAt(n)
      boxed(fst) -> boxed(snd)
    }
  }

  /** Creates a chunk backed by an array of booleans. */
  def booleans(values: Array[Boolean]): Chunk[Boolean] = Booleans(values)
  final case class Booleans(values: Array[Boolean]) extends Chunk[Boolean] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Boolean], Chunk[Boolean]) = {
      val (fst,snd) = values.splitAt(n)
      booleans(fst) -> booleans(snd)
    }
  }

  /** Creates a chunk backed by an array of bytes. */
  def bytes(values: Array[Byte]): Chunk[Byte] = Bytes(values)
  final case class Bytes(values: Array[Byte]) extends Chunk[Byte] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
      val (fst,snd) = values.splitAt(n)
      bytes(fst) -> bytes(snd)
    }
  }

  /** Creates a chunk backed by an array of shorts. */
  def shorts(values: Array[Short]): Chunk[Short] = Shorts(values)
  final case class Shorts(values: Array[Short]) extends Chunk[Short] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Short], Chunk[Short]) = {
      val (fst,snd) = values.splitAt(n)
      shorts(fst) -> shorts(snd)
    }
  }

  /** Creates a chunk backed by an array of ints. */
  def ints(values: Array[Int]): Chunk[Int] = Ints(values)
  final case class Ints(values: Array[Int]) extends Chunk[Int] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Int], Chunk[Int]) = {
      val (fst,snd) = values.splitAt(n)
      ints(fst) -> ints(snd)
    }
  }

  /** Creates a chunk backed by an array of longs. */
  def longs(values: Array[Long]): Chunk[Long] = Longs(values)
  final case class Longs(values: Array[Long]) extends Chunk[Long] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Long], Chunk[Long]) = {
      val (fst,snd) = values.splitAt(n)
      longs(fst) -> longs(snd)
    }
  }

  /** Creates a chunk backed by an array of floats. */
  def floats(values: Array[Float]): Chunk[Float] = Floats(values)
  final case class Floats(values: Array[Float]) extends Chunk[Float] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Float], Chunk[Float]) = {
      val (fst,snd) = values.splitAt(n)
      floats(fst) -> floats(snd)
    }
  }

  /** Creates a chunk backed by an array of doubles. */
  def doubles(values: Array[Double]): Chunk[Double] = Doubles(values)
  final case class Doubles(values: Array[Double]) extends Chunk[Double] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
    protected def splitAtChunk_(n: Int): (Chunk[Double], Chunk[Double]) = {
      val (fst,snd) = values.splitAt(n)
      doubles(fst) -> doubles(snd)
    }
  }

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
  }
}
