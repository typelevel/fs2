package fs2

import scala.reflect.{classTag, ClassTag}

/**
 * A strict, in-memory sequence of `A` values.
 *
 * Chunks can be constructed via various constructor methods on the `Chunk` companion object.
 *
 * Chunks are internally specialized for both single element chunks and unboxed arrays of primitives.
 *
 * Supports unboxed operations for booleans, bytes, longs, and doubles, using a
 * `Chunk` implementation that is backed by an array of primitives. When possible,
 * operations on `Chunk` preserves unboxed-ness. To get access the underlying
 * unboxed arrays, use `toBooleans`, `toBytes`, `toLongs`, and `toDoubles`.
 *
 * Note: the [[NonEmptyChunk]] type is a subtype of `Chunk` which is limited to chunks with at least
 * one element.
 */
trait Chunk[+A] { self =>

  /** Returns the number of elements in this chunk. */
  def size: Int

  /** If this chunk is non-empty, returns the first element of the chunk and the rest as a new chunk. Otherwise, returns none. */
  def uncons: Option[(A, Chunk[A])] =
    if (size == 0) None
    else Some(apply(0) -> drop(1))

  /** Gets the `i`-th element of this chunk, throwing an `IndexOutOfBoundsException` if the index is not in the range ``[0,size)`. */
  def apply(i: Int): A

  /** Copies the elements of this chunk in to the specified array at the specified start index. */
  def copyToArray[B >: A](xs: Array[B], start: Int = 0): Unit

  /** Returns a new chunk made up of the elemenets of this chunk without the first `n` elements. */
  def drop(n: Int): Chunk[A]

  /** Returns a new chunk made up of the first `n` elemenets of this chunk. */
  def take(n: Int): Chunk[A]

  /** Returns a new chunk made up of the elements of this chunk for which the specified predicate returns true. */
  def filter(f: A => Boolean): Chunk[A]

  /** Returns Some(this) if A =:= B. This function is pessimistic, in that it will assume None unless proven safe */
  def conform[B: ClassTag]: Option[Chunk[B]]

  /** Returns a new chunk consisting of the elements from the current chunk followed by the elements of the given chunks, in order */
  def concatAll[B >: A](chunks: Seq[Chunk[B]]): Chunk[B] =
    Chunk.indexedSeq(chunks.foldLeft(this.toVector: Vector[B]) { _ ++ _.toVector })

  /**
   * Reduces this chunk to a value of type `B` by applying `f` to each element, left to right,
   * passing the output of each invocation of `f` to the next invocation of `f` and starting with `z`.
   */
  def foldLeft[B](z: B)(f: (B,A) => B): B

  /**
   * Reduces this chunk to a value of type `B` by applying `f` to each element, right to left,
   * passing the output of each invocation of `f` to the next invocation of `f` and starting with `z`.
   */
  def foldRight[B](z: B)(f: (A,B) => B): B

  /** Returns the index of the first element for which `p` returns true. */
  def indexWhere(p: A => Boolean): Option[Int] = {
    val index = iterator.indexWhere(p)
    if (index < 0) None else Some(index)
  }

  /** Returns true if this chunk has no elements. */
  def isEmpty: Boolean = size == 0

  /** Returns true if this chunk has at least 1 element. */
  def nonEmpty: Boolean = size != 0

  /** Copies the elements of this chunk to an array. */
  def toArray[B >: A: ClassTag]: Array[B] = {
    val arr = new Array[B](size)
    copyToArray(arr)
    arr
  }

  /** Converts this chunk to a list. */
  def toList: List[A] = foldRight(Nil: List[A])(_ :: _)

  /** Converts this chunk to a vector. */
  def toVector: Vector[A] = foldLeft(Vector.empty[A])(_ :+ _)

  /** Maps and filters this chunk simultaneously using the supplied partial function. */
  def collect[B](pf: PartialFunction[A,B]): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size)
    iterator.collect(pf).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }

  /** Applies `f` to each element of this chunk, resulting in a new chunk of the same size. */
  def map[B](f: A => B): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size)
    iterator.map(f).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }

  /** Simultaneously folds and maps this chunk, returning the output of the fold and the transformed chunk. */
  def mapAccumulate[S,B](s0: S)(f: (S,A) => (S,B)): (S,Chunk[B]) = {
    val buf = new collection.mutable.ArrayBuffer[B](size)
    var s = s0
    for { c <- iterator } {
      val (newS, newC) = f(s, c)
      buf += newC
      s = newS
    }
    (s, Chunk.indexedSeq(buf))
  }

  /** Like `foldLeft` but each intermediate `B` value is output. */
  def scanLeft[B](z: B)(f: (B, A) => B): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size + 1)
    iterator.scanLeft(z)(f).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }

  /** Creates an iterator that iterates the elements of this chunk. The returned iterator is not thread safe. */
  def iterator: Iterator[A] = new Iterator[A] {
    private[this] var i = 0
    def hasNext = i < self.size
    def next = { val result = apply(i); i += 1; result }
  }

  /**
   * Converts this chunk to a `Chunk.Booleans`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of booleans, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toBooleans[B >: A](implicit ev: B =:= Boolean): Chunk.Booleans = this match {
    case c: Chunk.Booleans => c
    case other => new Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray, 0, size)
  }

  /**
   * Converts this chunk to a `Chunk.Bytes`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of bytes, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toBytes[B >: A](implicit ev: B =:= Byte): Chunk.Bytes = this match {
    case c: Chunk.Bytes => c
    case other => new Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
  }

  /**
   * Converts this chunk to a `Chunk.Longs`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of longs, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toLongs[B >: A](implicit ev: B =:= Long): Chunk.Longs = this match {
    case c: Chunk.Longs => c
    case other => new Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray, 0, size)
  }

  /**
   * Converts this chunk to a `Chunk.Doubles`, allowing access to the underlying array of elements.
   * If this chunk is already backed by an unboxed array of doubles, this method runs in constant time.
   * Otherwise, this method will copy of the elements of this chunk in to a single array.
   */
  def toDoubles[B >: A](implicit ev: B =:= Double): Chunk.Doubles = this match {
    case c: Chunk.Doubles => c
    case other => new Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray, 0, size)
  }

  override def toString: String = toList.mkString("Chunk(", ", ", ")")

  override def equals(a: Any): Boolean = a match {
    case c: Chunk[A] => c.toList == toList
    case _ => false
  }

  override def hashCode: Int = iterator.toStream.hashCode
}

trait MonomorphicChunk[+A] extends Chunk[A] {
  protected val tag: ClassTag[_]    // can't use type A because of invariance

  final def conform[B](implicit ev: ClassTag[B]): Option[Chunk[B]] =
    if (ev == tag) Some(this.asInstanceOf[Chunk[B]]) else None
}

trait PolymorphicChunk[+A] extends Chunk[A] {
  final def conform[B: ClassTag] = None
}

object Chunk {

  /** Empty chunk. */
  val empty: Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = throw new IllegalArgumentException(s"Chunk.empty($i)")
    def copyToArray[B >: Nothing](xs: Array[B], start: Int): Unit = ()
    def drop(n: Int) = empty
    def filter(f: Nothing => Boolean) = empty
    def take(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,Nothing) => B): B = z
    def foldRight[B](z: B)(f: (Nothing,B) => B): B = z
    def conform[B: ClassTag] = Some(this.asInstanceOf[Chunk[B]])
    override def concatAll[B](chunks: Seq[Chunk[B]]): Chunk[B] = Chunk.concat(chunks)
    override def map[B](f: Nothing => B) = empty
  }

  /** Creates a chunk of one element. */
  def singleton[A](a: A): NonEmptyChunk[A] = NonEmptyChunk.fromChunkUnsafe(new PolymorphicChunk[A] { self =>
    def size = 1
    def apply(i: Int) = if (i == 0) a else throw new IllegalArgumentException(s"Chunk.singleton($i)")
    def copyToArray[B >: A](xs: Array[B], start: Int): Unit = xs(start) = a
    def drop(n: Int) = if (n > 0) empty else self
    def filter(f: A => Boolean) = if (f(a)) self else empty
    def take(n: Int) = if (n > 0) self else empty
    def foldLeft[B](z: B)(f: (B,A) => B): B = f(z,a)
    def foldr[B](z: => B)(f: (A,=>B) => B): B = f(a,z)
    def foldRight[B](z: B)(f: (A,B) => B): B = f(a,z)
    override def map[B](f: A => B) = singleton(f(a))
  })

  /** Creates a chunk from an indexed sequence, using the existing index-based access provided by the indexed sequence. */
  def indexedSeq[A](a: collection.IndexedSeq[A]): Chunk[A] = new PolymorphicChunk[A] {
    def size = a.size
    override def isEmpty = a.isEmpty
    override def uncons = if (a.isEmpty) None else Some(a.head -> indexedSeq(a drop 1))
    def apply(i: Int) = a(i)
    def copyToArray[B >: A](xs: Array[B], start: Int): Unit = a.copyToArray(xs, start)
    def drop(n: Int) = indexedSeq(a.drop(n))
    def filter(f: A => Boolean) = indexedSeq(a.filter(f))
    def take(n: Int) = indexedSeq(a.take(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
    override def iterator = a.iterator
  }

  /** Creates a chunk from a sequence, using a lazy copy of the sequence to support indexed based access. */
  def seq[A](a: Seq[A]): Chunk[A] = new PolymorphicChunk[A] {
    lazy val vec = a.toIndexedSeq
    def size = a.size
    override def isEmpty = a.isEmpty
    override def uncons = if (a.isEmpty) None else Some(a.head -> seq(a drop 1))
    def apply(i: Int) = vec(i)
    def copyToArray[B >: A](xs: Array[B], start: Int): Unit = a.copyToArray(xs, start)
    def drop(n: Int) = seq(a.drop(n))
    def filter(f: A => Boolean) = seq(a.filter(f))
    def take(n: Int) = seq(a.take(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
    override def iterator = a.iterator
  }

  /** Creates a chunk backed by an unboxed array. */
  def booleans(values: Array[Boolean]): Chunk[Boolean] =
    new Booleans(values, 0, values.length)

  /** Creates a chunk backed by an unboxed array. */
  def booleans(values: Array[Boolean], offset: Int, size: Int): Chunk[Boolean] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Booleans(values, offset, size)
  }

  /** Creates a chunk backed by an unboxed array. */
  def bytes(values: Array[Byte]): Chunk[Byte] =
    new Bytes(values, 0, values.length)

  /** Creates a chunk backed by an unboxed array. */
  def bytes(values: Array[Byte], offset: Int, size: Int): Chunk[Byte] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Bytes(values, offset, size)
  }

  /** Creates a chunk backed by an unboxed array. */
  def longs(values: Array[Long]): Chunk[Long] =
    new Longs(values, 0, values.length)

  /** Creates a chunk backed by an unboxed array. */
  def longs(values: Array[Long], offset: Int, size: Int): Chunk[Long] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Longs(values, offset, size)
  }

  /** Creates a chunk backed by an unboxed array. */
  def doubles(values: Array[Double]): Chunk[Double] =
    new Doubles(values, 0, values.length)

  /** Creates a chunk backed by an unboxed array. */
  def doubles(values: Array[Double], offset: Int, size: Int): Chunk[Double] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Doubles(values, offset, size)
  }

  /** Concatenates the specified sequence of chunks in to a single chunk. */
  def concat[A](chunks: Seq[Chunk[A]]): Chunk[A] = {
    if (chunks.isEmpty)
      Chunk.empty
    else
      chunks.head concatAll chunks.tail
  }

  // justification of the performance of this class: http://stackoverflow.com/a/6823454
  // note that Class construction checks are nearly free when done at load time
  // for that reason, do NOT make the extractors into lazy vals or defs!
  private object Respecialization {
    // TODO drawn without verification from DarkDimius (measure and tune!)
    val Threshold = 4

    val ZZ = extractor { x: Boolean => true } disabled
    val BZ = extractor { x: Byte => true } disabled
    val LZ = extractor { x: Long => true } disabled
    val DZ = extractor { x: Double => true } disabled

    private[this] val AZ0 = extractor { x: AnyRef => true }
    def AZ[A] = AZ0.asInstanceOf[Extractor[A, Boolean]]

    val ZB = extractor { x: Boolean => 0.toByte } disabled
    val BB = extractor { x: Byte => 0.toByte } disabled
    val LB = extractor { x: Long => 0.toByte } disabled
    val DB = extractor { x: Double => 0.toByte } disabled

    private[this] val AB0 = extractor { x: AnyRef => 0.toByte }
    def AB[A] = AB0.asInstanceOf[Extractor[A, Byte]]

    val ZL = extractor { x: Boolean => 0L } disabled
    val BL = extractor { x: Byte => 0L } disabled
    val LL = extractor { x: Long => 0L }
    val DL = extractor { x: Double => 0L }

    private[this] val AL0 = extractor { x: AnyRef => 0L }
    def AL[A] = AL0.asInstanceOf[Extractor[A, Long]]

    val ZD = extractor { x: Boolean => 0D } disabled
    val BD = extractor { x: Byte => 0D } disabled
    val LD = extractor { x: Long => 0D }
    val DD = extractor { x: Double => 0D }

    private[this] val AD0 = extractor { x: AnyRef => 0D }
    def AD[A] = AD0.asInstanceOf[Extractor[A, Double]]

    private[this] val ZA0 = extractor { x: Boolean => new AnyRef }
    def ZA[A] = ZA0.asInstanceOf[Extractor[Boolean, A]]

    private[this] val BA0 = extractor { x: Byte => new AnyRef }
    def BA[A] = ZA0.asInstanceOf[Extractor[Byte, A]]

    private[this] val LA0 = extractor { x: Long => new AnyRef }
    def LA[A] = ZA0.asInstanceOf[Extractor[Long, A]]

    private[this] val DA0 = extractor { x: Double => new AnyRef }
    def DA[A] = ZA0.asInstanceOf[Extractor[Double, A]]

    // this one is sort of pointless, but it's here for uniformity
    private[this] val AA0 = extractor { x: AnyRef => new AnyRef }
    def AA[A, B] = ZA0.asInstanceOf[Extractor[A, B]]

    private[this] def extractor[A, B](sample: A => B): Extractor[A, B] =
      new Extractor(sample.getClass.getSuperclass.asInstanceOf[Class[A => B]])

    final class Extractor[A, B](clazz: Class[A => B], enabled: Boolean = true) {

      def disabled: Extractor[A, B] = new Extractor(clazz, enabled = false)

      def unapply(f: _ => _): Option[A => B] = {
        if (enabled && clazz == f.getClass.getSuperclass)
          Some(f.asInstanceOf[A => B])
        else
          None
      }
    }
  }

  // copy-pasted code below for each primitive
  // sadly, @specialized does not work here since the generated class names are
  // not human readable and we want to be able to use these type names in pattern
  // matching, e.g. `h.receive { case (bits: Booleans) #: h => /* do stuff unboxed */ } `

  /** Specialized chunk supporting unboxed operations on booleans. */
  final class Booleans private[Chunk](val values: Array[Boolean], val offset: Int, sz: Int) extends MonomorphicChunk[Boolean] { self =>
    protected val tag = classTag[Boolean]
    val size = sz min (values.length - offset)
    def at(i: Int): Boolean = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Boolean](xs: Array[B], start: Int): Unit = {
      if (xs.isInstanceOf[Array[Boolean]])
        System.arraycopy(values, offset, xs, start, sz)
      else
        values.iterator.slice(offset, offset + sz).copyToArray(xs, start)
    }
    def drop(n: Int) =
      if (n >= size) empty
      else new Booleans(values, offset + n, size - n)
    def filter(f: Boolean => Boolean) = {
      var i = offset
      val bound = offset + sz

      val values2 = new Array[Boolean](size)
      var size2 = 0

      while (i < bound) {
        if (f(values(i))) {
          values2(size2) = values(i)
          size2 += 1
        }

        i += 1
      }

      new Booleans(values2, 0, size2)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Booleans(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Boolean) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Boolean,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))

    override def concatAll[B >: Boolean](chunks: Seq[Chunk[B]]): Chunk[B] = {
      val conformed = chunks flatMap { _.conform[Boolean] }

      if (chunks.isEmpty) {
        this
      } else if ((chunks lengthCompare conformed.size) == 0) {
        val size = conformed.foldLeft(this.size)(_ + _.size)
        val arr = Array.ofDim[Boolean](size)
        var offset = 0

        if (!isEmpty) {
          copyToArray(arr, offset)
          offset += this.size
        }

        conformed.foreach { c =>
          if (!c.isEmpty) {
            c.copyToArray(arr, offset)
            offset += c.size
          }
        }
        Chunk.booleans(arr)
      } else {
        super.concatAll(chunks)
      }
    }

    // here be dragons; modify with EXTREME care
    override def map[B](f: Boolean => B): Chunk[B] = {
      import Respecialization._

      if (size >= Threshold) {
        f match {
          case ZZ(f) => mapZ(f).asInstanceOf[Chunk[B]]
          case ZB(f) => mapB(f).asInstanceOf[Chunk[B]]
          case ZL(f) => mapL(f).asInstanceOf[Chunk[B]]
          case ZD(f) => mapD(f).asInstanceOf[Chunk[B]]
          case _ => mapA(f)
        }
      } else {
        super.map(f)
      }
    }
    private[this] def mapZ(f: Boolean => Boolean): Chunk[Boolean] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Boolean](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      booleans(back)
    }
    private[this] def mapB(f: Boolean => Byte): Chunk[Byte] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Byte](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      bytes(back)
    }
    private[this] def mapL(f: Boolean => Long): Chunk[Long] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Long](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      longs(back)
    }
    private[this] def mapD(f: Boolean => Double): Chunk[Double] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Double](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      doubles(back)
    }
    private[this] def mapA[B](f: Boolean => B): Chunk[B] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Any](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      indexedSeq(back.toIndexedSeq.asInstanceOf[IndexedSeq[B]])
    }
  }

  /** Specialized chunk supporting unboxed operations on bytes. */
  final class Bytes private[Chunk](val values: Array[Byte], val offset: Int, sz: Int) extends MonomorphicChunk[Byte] { self =>
    protected val tag = classTag[Byte]
    val size = sz min (values.length - offset)
    def at(i: Int): Byte = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Byte](xs: Array[B], start: Int): Unit = {
      if (xs.isInstanceOf[Array[Byte]])
        System.arraycopy(values, offset, xs, start, sz)
      else
        values.iterator.slice(offset, offset + sz).copyToArray(xs)
    }
    def drop(n: Int) =
      if (n >= size) empty
      else new Bytes(values, offset + n, size - n)
    def filter(f: Byte => Boolean) = {
      var i = offset
      val bound = offset + sz

      val values2 = new Array[Byte](size)
      var size2 = 0

      while (i < bound) {
        if (f(values(i))) {
          values2(size2) = values(i)
          size2 += 1
        }

        i += 1
      }

      new Bytes(values2, 0, size2)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Bytes(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Byte) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Byte,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))

    override def concatAll[B >: Byte](chunks: Seq[Chunk[B]]): Chunk[B] = {
      val conformed = chunks flatMap { _.conform[Byte] }

      if (chunks.isEmpty) {
        this
      } else if ((chunks lengthCompare conformed.size) == 0) {
        val size = conformed.foldLeft(this.size)(_ + _.size)
        val arr = Array.ofDim[Byte](size)
        var offset = 0

        if (!isEmpty) {
          copyToArray(arr, offset)
          offset += this.size
        }

        conformed.foreach { c =>
          if (!c.isEmpty) {
            c.copyToArray(arr, offset)
            offset += c.size
          }
        }
        Chunk.bytes(arr)
      } else {
        super.concatAll(chunks)
      }
    }

    override def toString: String = s"Bytes(offset=$offset, sz=$sz, values=${values.toSeq})"

    // here be dragons; modify with EXTREME care
    override def map[B](f: Byte => B): Chunk[B] = {
      import Respecialization._

      if (size >= Threshold) {
        f match {
          case BZ(f) => mapZ(f).asInstanceOf[Chunk[B]]
          case BB(f) => mapB(f).asInstanceOf[Chunk[B]]
          case BL(f) => mapL(f).asInstanceOf[Chunk[B]]
          case BD(f) => mapD(f).asInstanceOf[Chunk[B]]
          case _ => mapA(f)
        }
      } else {
        super.map(f)
      }
    }
    private[this] def mapZ(f: Byte => Boolean): Chunk[Boolean] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Boolean](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      booleans(back)
    }
    private[this] def mapB(f: Byte => Byte): Chunk[Byte] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Byte](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      bytes(back)
    }
    private[this] def mapL(f: Byte => Long): Chunk[Long] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Long](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      longs(back)
    }
    private[this] def mapD(f: Byte => Double): Chunk[Double] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Double](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      doubles(back)
    }
    private[this] def mapA[B](f: Byte => B): Chunk[B] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Any](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      indexedSeq(back.toIndexedSeq.asInstanceOf[IndexedSeq[B]])
    }
  }

  /** Specialized chunk supporting unboxed operations on longs. */
  final class Longs private[Chunk](val values: Array[Long], val offset: Int, sz: Int) extends MonomorphicChunk[Long] { self =>
    protected val tag = classTag[Long]
    val size = sz min (values.length - offset)
    def at(i: Int): Long = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Long](xs: Array[B], start: Int): Unit = {
      if (xs.isInstanceOf[Array[Long]])
        System.arraycopy(values, offset, xs, start, sz)
      else
        values.iterator.slice(offset, offset + sz).copyToArray(xs)
    }
    def drop(n: Int) =
      if (n >= size) empty
      else new Longs(values, offset + n, size - n)
    def filter(f: Long => Boolean) = {
      var i = offset
      val bound = offset + sz

      val values2 = new Array[Long](size)
      var size2 = 0

      while (i < bound) {
        if (f(values(i))) {
          values2(size2) = values(i)
          size2 += 1
        }

        i += 1
      }

      new Longs(values2, 0, size2)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Longs(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Long) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Long,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))

    override def concatAll[B >: Long](chunks: Seq[Chunk[B]]): Chunk[B] = {
      val conformed = chunks flatMap { _.conform[Long] }

      if (chunks.isEmpty) {
        this
      } else if ((chunks lengthCompare conformed.size) == 0) {
        val size = conformed.foldLeft(this.size)(_ + _.size)
        val arr = Array.ofDim[Long](size)
        var offset = 0

        if (!isEmpty) {
          copyToArray(arr, offset)
          offset += this.size
        }

        conformed.foreach { c =>
          if (!c.isEmpty) {
            c.copyToArray(arr, offset)
            offset += c.size
          }
        }
        Chunk.longs(arr)
      } else {
        super.concatAll(chunks)
      }
    }

    // here be dragons; modify with EXTREME care
    override def map[B](f: Long => B): Chunk[B] = {
      import Respecialization._

      if (size >= Threshold) {
        f match {
          case LZ(f) => mapZ(f).asInstanceOf[Chunk[B]]
          case LB(f) => mapB(f).asInstanceOf[Chunk[B]]
          case LL(f) => mapL(f).asInstanceOf[Chunk[B]]
          case LD(f) => mapD(f).asInstanceOf[Chunk[B]]
          case _ => mapA(f)
        }
      } else {
        super.map(f)
      }
    }
    private[this] def mapZ(f: Long => Boolean): Chunk[Boolean] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Boolean](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      booleans(back)
    }
    private[this] def mapB(f: Long => Byte): Chunk[Byte] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Byte](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      bytes(back)
    }
    private[this] def mapL(f: Long => Long): Chunk[Long] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Long](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      longs(back)
    }
    private[this] def mapD(f: Long => Double): Chunk[Double] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Double](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      doubles(back)
    }
    private[this] def mapA[B](f: Long => B): Chunk[B] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Any](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      indexedSeq(back.toIndexedSeq.asInstanceOf[IndexedSeq[B]])
    }
  }

  /** Specialized chunk supporting unboxed operations on doubles. */
  final class Doubles private[Chunk](val values: Array[Double], val offset: Int, sz: Int) extends MonomorphicChunk[Double] { self =>
    protected val tag = classTag[Double]
    val size = sz min (values.length - offset)
    def at(i: Int): Double = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Double](xs: Array[B], start: Int): Unit = {
      if (xs.isInstanceOf[Array[Double]])
        System.arraycopy(values, offset, xs, start, sz)
      else
        values.iterator.slice(offset, offset + sz).copyToArray(xs)
    }
    def drop(n: Int) =
      if (n >= size) empty
      else new Doubles(values, offset + n, size - n)
    def filter(f: Double => Boolean) = {
      var i = offset
      val bound = offset + sz

      val values2 = new Array[Double](size)
      var size2 = 0

      while (i < bound) {
        if (f(values(i))) {
          values2(size2) = values(i)
          size2 += 1
        }

        i += 1
      }

      new Doubles(values2, 0, size2)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Doubles(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Double) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Double,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))

    override def concatAll[B >: Double](chunks: Seq[Chunk[B]]): Chunk[B] = {
      val conformed = chunks flatMap { _.conform[Double] }

      if (chunks.isEmpty) {
        this
      } else if ((chunks lengthCompare conformed.size) == 0) {
        val size = conformed.foldLeft(this.size)(_ + _.size)
        val arr = Array.ofDim[Double](size)
        var offset = 0

        if (!isEmpty) {
          copyToArray(arr, offset)
          offset += this.size
        }

        conformed.foreach { c =>
          if (!c.isEmpty) {
            c.copyToArray(arr, offset)
            offset += c.size
          }
        }
        Chunk.doubles(arr)
      } else {
        super.concatAll(chunks)
      }
    }

    // here be dragons; modify with EXTREME care
    override def map[B](f: Double => B): Chunk[B] = {
      import Respecialization._

      if (size >= Threshold) {
        f match {
          case DZ(f) => mapZ(f).asInstanceOf[Chunk[B]]
          case DB(f) => mapB(f).asInstanceOf[Chunk[B]]
          case DL(f) => mapL(f).asInstanceOf[Chunk[B]]
          case DD(f) => mapD(f).asInstanceOf[Chunk[B]]
          case _ => mapA(f)
        }
      } else {
        super.map(f)
      }
    }
    private[this] def mapZ(f: Double => Boolean): Chunk[Boolean] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Boolean](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      booleans(back)
    }
    private[this] def mapB(f: Double => Byte): Chunk[Byte] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Byte](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      bytes(back)
    }
    private[this] def mapL(f: Double => Long): Chunk[Long] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Long](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      longs(back)
    }
    private[this] def mapD(f: Double => Double): Chunk[Double] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Double](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      doubles(back)
    }
    private[this] def mapA[B](f: Double => B): Chunk[B] = {
      var i = offset
      val bound = size + offset
      val back = new Array[Any](size)
      while (i < bound) {
        back(i - offset) = f(values(i))
        i += 1
      }
      indexedSeq(back.toIndexedSeq.asInstanceOf[IndexedSeq[B]])
    }
  }
}

/**
 * A chunk which has at least one element.
 */
sealed trait NonEmptyChunk[+A] extends Chunk[A] {

  /** Like [[uncons]] but returns the head and tail directly instead of being wrapped in an `Option`. */
  def unconsNonEmpty: (A, Chunk[A])

  /** Returns the first element in the chunk. */
  def head: A = unconsNonEmpty._1

  /** Returns the all but the first element of the chunk. */
  def tail: Chunk[A] = unconsNonEmpty._2

  /** Returns the last element in the chunk. */
  def last: A = apply(size - 1)

  /** Like `foldLeft` but uses the first element of the chunk as the starting value. */
  def reduceLeft[A2 >: A](f: (A2, A) => A2): A2 = {
    val (hd, tl) = unconsNonEmpty
    tl.foldLeft(hd: A2)(f)
  }

  /** Like `foldRight` but uses the first element of the chunk as the starting value. */
  def reduceRight[A2 >: A](f: (A, A2) => A2): A2 = {
    if (tail.isEmpty) head
    else {
      val init = tail.take(tail.size - 1)
      val last = tail(tail.size - 1)
      val penutltimate = init.foldRight(last: A2)(f)
      f(head, penutltimate)
    }
  }

  override def map[B](f: A => B): NonEmptyChunk[B] =
    NonEmptyChunk.fromChunkUnsafe(super.map(f))

  override def mapAccumulate[S,B](s0: S)(f: (S,A) => (S,B)): (S,NonEmptyChunk[B]) = {
    val (s, c) = super.mapAccumulate(s0)(f)
    (s, NonEmptyChunk.fromChunkUnsafe(c))
  }

  override def scanLeft[B](z: B)(f: (B, A) => B): NonEmptyChunk[B] =
    NonEmptyChunk.fromChunkUnsafe(super.scanLeft(z)(f))
}

object NonEmptyChunk {

  /** Constructs a `NonEmptyChunk` from the specified head and tail values. */
  def apply[A](hd: A, tl: Chunk[A]): NonEmptyChunk[A] =
    fromChunkUnsafe(Chunk.concat(List(singleton(hd), tl)))

  /** Supports pattern matching on a non-empty chunk. */
  def unapply[A](c: NonEmptyChunk[A]): Some[(A, Chunk[A])] = Some(c.unconsNonEmpty)

  /** Constructs a `NonEmptyChunk` from a single element. */
  def singleton[A](a: A): NonEmptyChunk[A] = Chunk.singleton(a)

  /** Constructs a `NonEmptyChunk` from the specified, potentially empty, chunk. */
  def fromChunk[A](c: Chunk[A]): Option[NonEmptyChunk[A]] = c match {
    case c: NonEmptyChunk[A] => Some(c)
    case c =>
      if (c.isEmpty) None
      else Some(fromChunkUnsafe(c))
  }

  private[fs2] def fromChunkUnsafe[A](c: Chunk[A]): NonEmptyChunk[A] = c match {
    case c: NonEmptyChunk[A] => c
    case _ => new Wrapped(c)
  }

  private final class Wrapped[A](val underlying: Chunk[A]) extends NonEmptyChunk[A] {
    def unconsNonEmpty: (A, Chunk[A]) = underlying.uncons.get
    def apply(i: Int): A = underlying(i)
    def copyToArray[B >: A](xs: Array[B], start: Int): Unit = underlying.copyToArray(xs, start)
    def drop(n: Int): Chunk[A] = underlying.drop(n)
    def filter(f: A => Boolean): Chunk[A] = underlying.filter(f)
    def foldLeft[B](z: B)(f: (B, A) => B): B = underlying.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A, B) => B): B = underlying.foldRight(z)(f)
    def size: Int = underlying.size
    def take(n: Int): Chunk[A] = underlying.take(n)
    def conform[B: ClassTag] = underlying.conform[B]
    override def concatAll[B >: A](chunks: Seq[Chunk[B]]) = underlying.concatAll(chunks)
    override def toBooleans[B >: A](implicit ev: B =:= Boolean): Chunk.Booleans = underlying.toBooleans(ev)
    override def toBytes[B >: A](implicit ev: B =:= Byte): Chunk.Bytes = underlying.toBytes(ev)
    override def toLongs[B >: A](implicit ev: B =:= Long): Chunk.Longs = underlying.toLongs(ev)
    override def toDoubles[B >: A](implicit ev: B =:= Double): Chunk.Doubles = underlying.toDoubles(ev)
  }
}
