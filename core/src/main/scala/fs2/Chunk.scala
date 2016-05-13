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
  override def toString = toList.mkString("Chunk(", ", ", ")")
  override def equals(a: Any) = a.asInstanceOf[Chunk[A]].toList == toList
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

  def booleans(values: Array[Boolean]): Booleans =
    new Booleans(values, 0, values.length)

  def bytes(values: Array[Byte]): Bytes =
    new Bytes(values, 0, values.length)

  def longs(values: Array[Long]): Longs =
    new Longs(values, 0, values.length)

  def doubles(values: Array[Double]): Doubles =
    new Doubles(values, 0, values.length)

  // copy-pasted code below for each primitive
  // sadly, @specialized does not work here since the generated class names are
  // not human readable and we want to be able to use these type names in pattern
  // matching, e.g. `h.receive { case (bits: Booleans) #: h => /* do stuff unboxed */ } `

  class Booleans(val values: Array[Boolean], val offset: Int, sz: Int) extends Chunk[Boolean] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Boolean = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Boolean](xs: Array[B]): Unit =
      values.iterator.slice(offset, offset + sz).copyToArray(xs)
    def drop(n: Int) =
      if (n >= size) empty
      else new Booleans(values, offset + n, size - n)
    def filter(f: Boolean => Boolean) = {
      val arr = values.iterator.slice(offset, offset + sz).filter(f).toArray
      new Booleans(arr, 0, arr.length)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Booleans(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Boolean) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Boolean,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))
  }
  class Bytes(val values: Array[Byte], val offset: Int, sz: Int) extends Chunk[Byte] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Byte = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Byte](xs: Array[B]): Unit =
      values.iterator.slice(offset, offset + sz).copyToArray(xs)
    def drop(n: Int) =
      if (n >= size) empty
      else new Bytes(values, offset + n, size - n)
    def filter(f: Byte => Boolean) = {
      val arr = values.iterator.slice(offset, offset + sz).filter(f).toArray
      new Bytes(arr, 0, arr.length)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Bytes(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Byte) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Byte,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))
    override def toString: String = s"Bytes(offset=$offset, sz=$sz, values=${values.toSeq})"
  }
  class Longs(val values: Array[Long], val offset: Int, sz: Int) extends Chunk[Long] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Long = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Long](xs: Array[B]): Unit =
      values.iterator.slice(offset, offset + sz).copyToArray(xs)
    def drop(n: Int) =
      if (n >= size) empty
      else new Longs(values, offset + n, size - n)
    def filter(f: Long => Boolean) = {
      val arr = values.iterator.slice(offset, offset + sz).filter(f).toArray
      new Longs(arr, 0, arr.length)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Longs(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Long) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Long,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))
  }
  class Doubles(val values: Array[Double], val offset: Int, sz: Int) extends Chunk[Double] {
  self =>
    val size = sz min (values.length - offset)
    def at(i: Int): Double = values(offset + i)
    def apply(i: Int) = values(offset + i)
    def copyToArray[B >: Double](xs: Array[B]): Unit =
      values.iterator.slice(offset, offset + sz).copyToArray(xs)
    def drop(n: Int) =
      if (n >= size) empty
      else new Doubles(values, offset + n, size - n)
    def filter(f: Double => Boolean) = {
      val arr = values.iterator.slice(offset, offset + sz).filter(f).toArray
      new Doubles(arr, 0, arr.length)
    }
    def take(n: Int) =
      if (n >= size) self
      else new Doubles(values, offset, n)
    def foldLeft[B](z: B)(f: (B,Double) => B): B =
      (0 until size).foldLeft(z)((z,i) => f(z, at(i)))
    def foldRight[B](z: B)(f: (Double,B) => B): B =
      ((size-1) to 0 by -1).foldLeft(z)((tl,hd) => f(at(hd), tl))
  }
}
