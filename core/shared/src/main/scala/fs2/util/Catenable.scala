package fs2.util

import Catenable._

/*
 * Trivial catenable sequence. Supports O(1) append, and (amortized)
 * O(1) `uncons`, such that walking the sequence via N successive `uncons`
 * steps takes O(N). Like a difference list, conversion to a `Stream[A]`
 * takes linear time, regardless of how the sequence is built up.
 */
abstract class Catenable[+A] {
  def uncons: Option[(A, Catenable[A])] = {
    @annotation.tailrec
    def go(c: Catenable[A], rights: List[Catenable[A]]): Option[(A,Catenable[A])] = c match {
      case Empty => rights match {
        case Nil => None
        case c :: rights => go(c, rights)
      }
      case Single(a) => Some(a -> (if (rights.isEmpty) empty else rights.reduceRight(Append(_,_))))
      case Append(l,r) => go(l, r :: rights)
    }
    go(this, List())
  }
  def isEmpty: Boolean = this match {
    case Empty => true
    case _ => false // okay since `append` smart constructor guarantees each branch nonempty
  }

  def ++[A2>:A](c: Catenable[A2])(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(this, c)

  def ::[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    this.push(a)

  def push[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Catenable[A2] =
    append(single(a), this)

  def toStream: Stream[A] = uncons match {
    case None => Stream.empty
    case Some((hd, tl)) => hd #:: tl.toStream
  }

  def map[B](f: A => B): Catenable[B] = Catenable.fromSeq(toStream.map(f))
}

object Catenable {
  case object Empty extends Catenable[Nothing]
  private case class Single[A](a: A) extends Catenable[A]
  private case class Append[A](left: Catenable[A], right: Catenable[A]) extends Catenable[A]

  val empty: Catenable[Nothing] = Empty
  def single[A](a: A): Catenable[A] = Single(a)
  def append[A](c: Catenable[A], c2: Catenable[A]): Catenable[A] = c match {
    case Empty => c2
    case _ => c2 match {
      case Empty => c
      case _ => Append(c,c2)
    }
  }
  def fromSeq[A](s: Seq[A]): Catenable[A] =
    if (s.isEmpty) empty
    else s.view.map(single).reduceRight(Append(_,_))

  def apply[A](as: A*): Catenable[A] = fromSeq(as)
}
