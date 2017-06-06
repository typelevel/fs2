package fs2

import cats.{ Applicative, Eval, Foldable, Traverse }
import cats.implicits._

import Catenable._

/**
 * Trivial catenable sequence. Supports O(1) append, and (amortized)
 * O(1) `uncons`, such that walking the sequence via N successive `uncons`
 * steps takes O(N). Like a difference list, conversion to a `Seq[A]`
 * takes linear time, regardless of how the sequence is built up.
 */
sealed abstract class Catenable[+A] {

  /** Returns the head and tail of this catenable if non empty, none otherwise. Amortized O(1). */
  final def uncons: Option[(A, Catenable[A])] = {
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
  def isEmpty: Boolean

  /** Returns false if there are no elements in this collection. */
  def nonEmpty: Boolean = !isEmpty

  /** Concatenates this with `c` in O(1) runtime. */
  final def ++[A2>:A](c: Catenable[A2]): Catenable[A2] =
    append(this, c)

  /** Returns a new catenable consisting of `a` followed by this. O(1) runtime. */
  final def cons[A2>:A](a: A2): Catenable[A2] =
    append(singleton(a), this)

  /** Alias for [[cons]]. */
  final def +:[A2>:A](a: A2): Catenable[A2] =
    cons(a)

  /** Returns a new catenable consisting of this followed by `a`. O(1) runtime. */
  final def snoc[A2>:A](a: A2): Catenable[A2] =
    append(this, singleton(a))

  /** Alias for [[snoc]]. */
  final def :+[A2>:A](a: A2): Catenable[A2] =
    snoc(a)

  /** Applies the supplied function to each element and returns a new catenable. */
  final def map[B](f: A => B): Catenable[B] =
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

  /** Converts to a vector. */
  final def toVector: Vector[A] = {
    val builder = new scala.collection.immutable.VectorBuilder[A]()
    foreach { a => builder += a; () }
    builder.result
  }

  override def toString = "Catenable(..)"
}

object Catenable {
  private final case object Empty extends Catenable[Nothing] {
    def isEmpty: Boolean = true
  }
  private final case class Single[A](a: A) extends Catenable[A] {
    def isEmpty: Boolean = false
  }
  private final case class Append[A](left: Catenable[A], right: Catenable[A]) extends Catenable[A] {
    def isEmpty: Boolean = false // b/c `append` constructor doesn't allow either branch to be empty
  }

  /** Empty catenable. */
  val empty: Catenable[Nothing] = Empty

  /** Creates a catenable of 1 element. */
  def singleton[A](a: A): Catenable[A] = Single(a)

  /** Appends two catenables. */
  def append[A](c: Catenable[A], c2: Catenable[A]): Catenable[A] =
    if (c.isEmpty) c2
    else if (c2.isEmpty) c
    else Append(c, c2)

  /** Creates a catenable from the specified sequence. */
  def fromSeq[A](s: Seq[A]): Catenable[A] =
    if (s.isEmpty) empty
    else s.view.reverse.map(singleton).reduceLeft((x, y) => Append(y, x))

  /** Creates a catenable from the specified elements. */
  def apply[A](as: A*): Catenable[A] = {
    as match {
      case w: collection.mutable.WrappedArray[A] =>
        if (w.isEmpty) empty
        else if (w.size == 1) singleton(w.head)
        else {
          val arr: Array[A] = w.array
          var c: Catenable[A] = singleton(arr.last)
          var idx = arr.size - 2
          while (idx >= 0) {
            c = Append(singleton(arr(idx)), c)
            idx -= 1
          }
          c
        }
      case _ => fromSeq(as)
    }
  }

  implicit val traverseInstance: Traverse[Catenable] = new Traverse[Catenable] {
    def foldLeft[A, B](fa: Catenable[A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
    def foldRight[A, B](fa: Catenable[A], b: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = Foldable[List].foldRight(fa.toList, b)(f)
    override def toList[A](fa: Catenable[A]): List[A] = fa.toList
    override def isEmpty[A](fa: Catenable[A]): Boolean = fa.isEmpty
    def traverse[F[_], A, B](fa: Catenable[A])(f: A => F[B])(implicit G: Applicative[F]): F[Catenable[B]] =
      Traverse[List].traverse(fa.toList)(f).map(Catenable.apply)
  }
}
