package fs2

import cats.Eval
import scala.reflect.ClassTag

abstract class Chunk[+O] extends Segment[O,Unit] { self =>
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
  def size: Int
  def apply(i: Int): O

  final def isEmpty = size == 0
  final def nonEmpty = size > 0

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
  def toBooleans[B >: O](implicit ev: B =:= Boolean): Chunk.Booleans = this match {
    case c: Chunk.Booleans => c
    case other => Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Bytes`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toBytes[B >: O](implicit ev: B =:= Byte): Chunk.Bytes = this match {
    case c: Chunk.Bytes => c
    case other => Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Shorts`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toShorts[B >: O](implicit ev: B =:= Short): Chunk.Shorts = this match {
    case c: Chunk.Shorts => c
    case other => Chunk.Shorts(this.asInstanceOf[Chunk[Short]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Ints`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toInts[B >: O](implicit ev: B =:= Int): Chunk.Ints = this match {
    case c: Chunk.Ints => c
    case other => Chunk.Ints(this.asInstanceOf[Chunk[Int]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Longs`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of longs, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toLongs[B >: O](implicit ev: B =:= Long): Chunk.Longs = this match {
    case c: Chunk.Longs => c
    case other => Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Floats`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of doubles, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toFloats[B >: O](implicit ev: B =:= Float): Chunk.Floats = this match {
    case c: Chunk.Floats => c
    case other => Chunk.Floats(this.asInstanceOf[Chunk[Float]].toArray)
  }

  /**
   * Converts this chunk to a `Chunk.Doubles`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of doubles, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toDoubles[B >: O](implicit ev: B =:= Double): Chunk.Doubles = this match {
    case c: Chunk.Doubles => c
    case other => Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray)
  }

  override def unconsChunk: Either[Unit, (Chunk[O],Segment[O,Unit])] =
    if (isEmpty) Left(()) else Right(this -> Chunk.empty)
  override def foreachChunk(f: Chunk[O] => Unit): Unit = f(this)
  override def toChunk = this
  override def toChunks = Catenable.single(this)
  override def toVector: Vector[O] = {
    val buf = new collection.immutable.VectorBuilder[O]
    var i = 0
    while (i < size) {
      buf += apply(i)
      i += 1
    }
    buf.result
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
    override def stage0 = (_,_,_,_,done) => Eval.now(Segment.step(empty_)(done(())))
    override def unconsChunk: Either[Unit, (Chunk[Nothing],Segment[Nothing,Unit])] = Left(())
    override def foreachChunk(f: Chunk[Nothing] => Unit): Unit = ()
    override def toVector: Vector[Nothing] = Vector.empty
    override def toString = "empty"
  }
  def empty[A]: Chunk[A] = empty_

  def singleton[A](a: A): Chunk[A] = new Chunk[A] {
    def size = 1
    def apply(i: Int) = { require (i == 0); a }
  }

  def vector[A](a: Vector[A]): Chunk[A] = {
    if (a.isEmpty) empty
    else new Chunk[A] {
      def size = a.length
      def apply(i: Int) = a(i)
      override def toVector = a
    }
  }

  def indexedSeq[A](a: IndexedSeq[A]): Chunk[A] = {
    if (a.isEmpty) empty
    else new Chunk[A] {
      def size = a.length
      def apply(i: Int) = a(i)
      override def toVector = a.toVector
    }
  }

  def seq[A](a: Seq[A]): Chunk[A] = indexedSeq(a.toIndexedSeq)

  def apply[A](as: A*): Chunk[A] = seq(as)

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

  def boxed[A](values: Array[A]): Boxed[A] = Boxed(values)
  final case class Boxed[A](values: Array[A]) extends Chunk[A] {
    def size = values.length
    def apply(i: Int) = values(i)
  }

  def booleans(values: Array[Boolean]): Booleans = Booleans(values)
  final case class Booleans(values: Array[Boolean]) extends Chunk[Boolean] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def bytes(values: Array[Byte]): Bytes = Bytes(values)
  final case class Bytes(values: Array[Byte]) extends Chunk[Byte] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def shorts(values: Array[Short]): Shorts = Shorts(values)
  final case class Shorts(values: Array[Short]) extends Chunk[Short] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def ints(values: Array[Int]): Ints = Ints(values)
  final case class Ints(values: Array[Int]) extends Chunk[Int] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def longs(values: Array[Long]): Longs = Longs(values)
  final case class Longs(values: Array[Long]) extends Chunk[Long] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def floats(values: Array[Float]): Floats = Floats(values)
  final case class Floats(values: Array[Float]) extends Chunk[Float] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }

  def doubles(values: Array[Double]): Doubles = Doubles(values)
  final case class Doubles(values: Array[Double]) extends Chunk[Double] {
    def size = values.length
    def apply(i: Int) = values(i)
    def at(i: Int) = values(i)
  }
}
