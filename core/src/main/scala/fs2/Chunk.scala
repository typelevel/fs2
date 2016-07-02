package fs2

import scala.reflect.ClassTag

/**
 * Chunk represents a strict, in-memory sequence of `A` values.
 */
trait Chunk[+A] { self =>
  def size: Int
  def uncons: Option[(A, Chunk[A])] =
    if (size == 0) None
    else Some(apply(0) -> drop(1))
  def apply(i: Int): A
  def copyToArray[B >: A](xs: Array[B]): Unit
  def drop(n: Int): Chunk[A]
  def take(n: Int): Chunk[A]
  def filter(f: A => Boolean): Chunk[A]
  def foldLeft[B](z: B)(f: (B,A) => B): B
  def foldRight[B](z: B)(f: (A,B) => B): B
  def indexWhere(p: A => Boolean): Option[Int] = {
    val index = iterator.indexWhere(p)
    if (index < 0) None else Some(index)
  }
  def isEmpty = size == 0
  def toArray[B >: A: ClassTag]: Array[B] = {
    val arr = new Array[B](size)
    copyToArray(arr)
    arr
  }
  def toList = foldRight(Nil: List[A])(_ :: _)
  def toVector = foldLeft(Vector.empty[A])(_ :+ _)
  def collect[B](pf: PartialFunction[A,B]): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size)
    iterator.collect(pf).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }
  def map[B](f: A => B): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size)
    iterator.map(f).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }
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
  def scanLeft[B](z: B)(f: (B, A) => B): Chunk[B] = {
    val buf = new collection.mutable.ArrayBuffer[B](size + 1)
    iterator.scanLeft(z)(f).copyToBuffer(buf)
    Chunk.indexedSeq(buf)
  }
  def iterator: Iterator[A] = new Iterator[A] {
    var i = 0
    def hasNext = i < self.size
    def next = { val result = apply(i); i += 1; result }
  }
  def toBooleans[B >: A](implicit ev: B =:= Boolean): Chunk.Booleans = this match {
    case c: Chunk.Booleans => c
    case other => new Chunk.Booleans(this.asInstanceOf[Chunk[Boolean]].toArray, 0, size)
  }
  def toBytes[B >: A](implicit ev: B =:= Byte): Chunk.Bytes = this match {
    case c: Chunk.Bytes => c
    case other => new Chunk.Bytes(this.asInstanceOf[Chunk[Byte]].toArray, 0, size)
  }
  def toLongs[B >: A](implicit ev: B =:= Long): Chunk.Longs = this match {
    case c: Chunk.Longs => c
    case other => new Chunk.Longs(this.asInstanceOf[Chunk[Long]].toArray, 0, size)
  }
  def toDoubles[B >: A](implicit ev: B =:= Double): Chunk.Doubles = this match {
    case c: Chunk.Doubles => c
    case other => new Chunk.Doubles(this.asInstanceOf[Chunk[Double]].toArray, 0, size)
  }
  override def toString = toList.mkString("Chunk(", ", ", ")")
  override def equals(a: Any) = a match {
    case c: Chunk[A] => c.toList == toList
    case _ => false
  }
  override def hashCode = iterator.toStream.hashCode
}

object Chunk {
  val empty: Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = throw new IllegalArgumentException(s"Chunk.empty($i)")
    def copyToArray[B >: Nothing](xs: Array[B]): Unit = ()
    def drop(n: Int) = empty
    def filter(f: Nothing => Boolean) = empty
    def take(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,Nothing) => B): B = z
    def foldRight[B](z: B)(f: (Nothing,B) => B): B = z
    override def map[B](f: Nothing => B) = empty
  }

  def singleton[A](a: A): Chunk[A] = new Chunk[A] { self =>
    def size = 1
    def apply(i: Int) = if (i == 0) a else throw new IllegalArgumentException(s"Chunk.singleton($i)")
    def copyToArray[B >: A](xs: Array[B]): Unit = xs(0) = a
    def drop(n: Int) = if (n > 0) empty else self
    def filter(f: A => Boolean) = if (f(a)) self else empty
    def take(n: Int) = if (n > 0) self else empty
    def foldLeft[B](z: B)(f: (B,A) => B): B = f(z,a)
    def foldr[B](z: => B)(f: (A,=>B) => B): B = f(a,z)
    def foldRight[B](z: B)(f: (A,B) => B): B = f(a,z)
    override def map[B](f: A => B) = singleton(f(a))
  }

  def indexedSeq[A](a: collection.IndexedSeq[A]): Chunk[A] = new Chunk[A] {
    def size = a.size
    override def isEmpty = a.isEmpty
    override def uncons = if (a.isEmpty) None else Some(a.head -> indexedSeq(a drop 1))
    def apply(i: Int) = a(i)
    def copyToArray[B >: A](xs: Array[B]): Unit = a.copyToArray(xs)
    def drop(n: Int) = indexedSeq(a.drop(n))
    def filter(f: A => Boolean) = indexedSeq(a.filter(f))
    def take(n: Int) = indexedSeq(a.take(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
    override def iterator = a.iterator
  }

  def seq[A](a: Seq[A]): Chunk[A] = new Chunk[A] {
    lazy val vec = a.toIndexedSeq
    def size = a.size
    override def isEmpty = a.isEmpty
    override def uncons = if (a.isEmpty) None else Some(a.head -> seq(a drop 1))
    def apply(i: Int) = vec(i)
    def copyToArray[B >: A](xs: Array[B]): Unit = a.copyToArray(xs)
    def drop(n: Int) = seq(a.drop(n))
    def filter(f: A => Boolean) = seq(a.filter(f))
    def take(n: Int) = seq(a.take(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
    override def iterator = a.iterator
  }

  def booleans(values: Array[Boolean]): Chunk[Boolean] =
    new Booleans(values, 0, values.length)

  def booleans(values: Array[Boolean], offset: Int, size: Int): Chunk[Boolean] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Booleans(values, offset, size)
  }

  def bytes(values: Array[Byte]): Chunk[Byte] =
    new Bytes(values, 0, values.length)

  def bytes(values: Array[Byte], offset: Int, size: Int): Chunk[Byte] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Bytes(values, offset, size)
  }

  def longs(values: Array[Long]): Chunk[Long] =
    new Longs(values, 0, values.length)

  def longs(values: Array[Long], offset: Int, size: Int): Chunk[Long] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Longs(values, offset, size)
  }

  def doubles(values: Array[Double]): Chunk[Double] =
    new Doubles(values, 0, values.length)

  def doubles(values: Array[Double], offset: Int, size: Int): Chunk[Double] = {
    require(offset >= 0 && offset <= values.size)
    require(offset + size <= values.size)
    new Doubles(values, offset, size)
  }

  // justification of the performance of this class: http://stackoverflow.com/a/6823454
  // note that Class construction checks are nearly free when done at load time
  // for that reason, do NOT make the extractors into lazy vals or defs!
  private object Respecialization {
    // TODO set by completely random guess. please tune!
    val Threshold = 0

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

  final class Booleans private[Chunk](val values: Array[Boolean], val offset: Int, sz: Int) extends Chunk[Boolean] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Boolean = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Boolean](xs: Array[B]): Unit = {
      if (xs.isInstanceOf[Array[Boolean]])
        System.arraycopy(values, offset, xs, 0, sz)
      else
        values.iterator.slice(offset, offset + sz).copyToArray(xs)
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
  final class Bytes private[Chunk](val values: Array[Byte], val offset: Int, sz: Int) extends Chunk[Byte] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Byte = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Byte](xs: Array[B]): Unit = {
      if (xs.isInstanceOf[Array[Byte]])
        System.arraycopy(values, offset, xs, 0, sz)
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
  final class Longs private[Chunk](val values: Array[Long], val offset: Int, sz: Int) extends Chunk[Long] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Long = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Long](xs: Array[B]): Unit = {
      if (xs.isInstanceOf[Array[Long]])
        System.arraycopy(values, offset, xs, 0, sz)
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
  final class Doubles private[Chunk](val values: Array[Double], val offset: Int, sz: Int) extends Chunk[Double] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Double = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Double](xs: Array[B]): Unit = {
      if (xs.isInstanceOf[Array[Double]])
        System.arraycopy(values, offset, xs, 0, sz)
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
