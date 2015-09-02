package streams

sealed trait Chunk[+A] {
  def size: Int
  def uncons: Option[(A, Chunk[A])] =
    if (size == 0) None
    else Some(apply(0) -> drop(1))
  def apply(i: Int): A
  def drop(n: Int): Chunk[A]
  def foldLeft[B](z: B)(f: (B,A) => B): B
  def foldRight[B](z: B)(f: (A,B) => B): B
  def isEmpty = size == 0
  def toList = foldRight(Nil: List[A])(_ :: _)
  override def toString = toList.mkString("Chunk(", ", ", ")")
}

object Chunk {
  val empty: Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = throw new IllegalArgumentException(s"Chunk.empty($i)")
    def drop(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,Nothing) => B): B = z
    def foldRight[B](z: B)(f: (Nothing,B) => B): B = z
  }
  def singleton[A](a: A): Chunk[A] = new Chunk[A] {
    def size = 1
    def apply(i: Int) = if (i == 0) a else throw new IllegalArgumentException(s"Chunk.singleton($i)")
    def drop(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,A) => B): B = f(z,a)
    def foldr[B](z: => B)(f: (A,=>B) => B): B = f(a,z)
    def foldRight[B](z: B)(f: (A,B) => B): B = f(a,z)
  }
  def array[A](a: Array[A], offset: Int = 0): Chunk[A] = new Chunk[A] {
    def size = a.length - offset
    def apply(i: Int) = a(offset + i)
    def drop(n: Int) = if (n >= size) empty else array(a, offset + n)
    def foldLeft[B](z: B)(f: (B,A) => B): B = {
      var res = z
      (offset until a.length).foreach { i => res = f(res, a(i)) }
      res
    }
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
  }
  def seq[A](a: Seq[A]): Chunk[A] = new Chunk[A] {
    def size = a.size
    def apply(i: Int) = a(i)
    def drop(n: Int) = seq(a.drop(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
    def foldRight[B](z: B)(f: (A,B) => B): B =
      a.reverseIterator.foldLeft(z)((b,a) => f(a,b))
  }
}
