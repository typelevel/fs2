package fs2.util

import Catenable._

/**
 * Trivial catenable sequence. Supports O(1) append, and (amortized)
 * O(1) `uncons`, such that walking the sequence via N successive `uncons`
 * steps takes O(N). Like a difference list, conversion to a `Seq[A]`
 * takes linear time, regardless of how the sequence is built up.
 */
sealed abstract class Catenable[+A] {

  /** Returns the head and tail of this catenable if non empty, none otherwise. Amortized O(1). */
  def uncons: Option[(A, Catenable[A])] = {
    var c: Catenable[A] = this
    val rights = new collection.mutable.ArrayBuffer[Catenable[A]]
    var result: Option[(A, Catenable[A])] = null
    while (result eq null) {
      c match {
        case Empty =>
          if (rights.isEmpty) {
            result = None
          } else {
            c = rights.last
            rights.trimEnd(1)
          }
        case Single(a) =>
          val next = if (rights.isEmpty) empty else rights.reduceLeft((x, y) => Append(y,x))
          result = Some(a -> next)
        case Append(l, r) => c = l; rights += r
      }
    }
    result
  }

  /** Returns true if there are no elements in this collection. */
  def isEmpty: Boolean = uncons.isEmpty

  /** Concatenates this with `c` in O(1) runtime. */
  def ++[A2>:A](c: Catenable[A2])(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(this, c)

  /** Returns a new catenable consisting of `a` followed by this. O(1) runtime. */
  final def cons[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(single(a), this)

  /** Alias for [[cons]]. */
  final def +:[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    cons(a)

  /** Deprecated alias for [[cons]]. */
  @deprecated("0.9.3", "Use cons or +: instead")
  def push[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(single(a), this)

  /** Deprecated alias for [[cons]]. */
  @deprecated("0.9.3", "Use cons or +: instead")
  def ::[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(single(a), this)

  /** Returns a new catenable consisting of this followed by `a`. O(1) runtime. */
  final def snoc[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(this, single(a))

  /** Alias for [[snoc]]. */
  final def :+[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    snoc(a)

  /** Applies the supplied function to each element and returns a new catenable. */
  def map[B](f: A => B): Catenable[B] =
    foldLeft(empty: Catenable[B])((acc, a) => acc :+ f(a))

  /** Folds over the elements from left to right using the supplied initial value and function. */
  final def foldLeft[B](z: B)(f: (B, A) => B): B = {
    var result = z
    foreach(a => result = f(result, a))
    result
  }

  /** Applies the supplied function to each element, left to right. */
  final def foreach(f: A => Unit): Unit = {
    var c: Catenable[A] = this
    val rights = new collection.mutable.ArrayBuffer[Catenable[A]]
    while (c ne null) {
      c match {
        case Empty =>
          if (rights.isEmpty) {
            c = null
          } else {
            c = rights.last
            rights.trimEnd(1)
          }
        case Single(a) =>
          f(a)
          c = if (rights.isEmpty) Empty else rights.reduceLeft((x, y) => Append(y,x))
          rights.clear()
        case Append(l, r) => c = l; rights += r
      }
    }
  }

  /** Converts to a list. */
  final def toList: List[A] = {
    val builder = List.newBuilder[A]
    foreach { a => builder += a; () }
    builder.result
  }

  @deprecated("0.9.3", "Use toList instead")
  def toStream: scala.collection.immutable.Stream[A] =
    toList.toStream

  override def toString = "Catenable(..)"
}

object Catenable {
  private final case object Empty extends Catenable[Nothing] {
    override def isEmpty: Boolean = true
  }
  private final case class Single[A](a: A) extends Catenable[A] {
    override def isEmpty: Boolean = false
  }
  private final case class Append[A](left: Catenable[A], right: Catenable[A]) extends Catenable[A] {
    override def isEmpty: Boolean = false // b/c `append` constructor doesn't allow either branch to be empty
  }

  /** Empty catenable. */
  val empty: Catenable[Nothing] = Empty

  /** Creates a catenable of 1 element. */
  def single[A](a: A): Catenable[A] = Single(a)

  /** Appends two catenables. */
  def append[A](c: Catenable[A], c2: Catenable[A]): Catenable[A] =
    if (c.isEmpty) c2
    else if (c2.isEmpty) c
    else Append(c, c2)

  /** Creates a catenable from the specified sequence. */
  def fromSeq[A](s: Seq[A]): Catenable[A] =
    if (s.isEmpty) empty
    else s.view.reverse.map(single).reduceLeft((x, y) => Append(y, x))

  /** Creates a catenable from the specified elements. */
  def apply[A](as: A*): Catenable[A] = {
    as match {
      case w: collection.mutable.WrappedArray[A] =>
        if (w.isEmpty) empty
        else if (w.size == 1) single(w.head)
        else {
          val arr: Array[A] = w.array
          var c: Catenable[A] = single(arr.last)
          var idx = arr.size - 2
          while (idx >= 0) {
            c = Append(single(arr(idx)), c)
            idx -= 1
          }
          c
        }
      case _ => fromSeq(as)
    }
  }
}
