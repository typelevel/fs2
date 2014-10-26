package scalaz.stream

import org.scalacheck.{ Arbitrary, Gen, Properties }
import org.scalacheck.Prop._
import scalaz.Equal
import scalaz.std.anyVal._
import scalaz.syntax.equal._

object SplitSpec extends Properties("split") {
  import split._

  case class Elt(value: Int)

  implicit val equalElt: Equal[Elt] = Equal[Int].contramap(_.value)

  implicit val arbitraryElt: Arbitrary[Elt] = Arbitrary(
    Gen.oneOf(0 until 8).map(Elt(_))
  )

  property("default") = forAll { (v: Vector[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.default.split).toList

    res.sameElements(Vector(v))
  }

  property("oneOf") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).split).toList

    res.sameElements(Vec.whenElt[Elt](a => delims.exists(a === _))(v))
  }

  property("onSubsequence") = forAll { (v: Vector[Elt], seq: Vector[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.onSubsequence(seq).split).toList

    res.sameElements(Vec.onSubsequence(seq)(v))
  }

  property("condense") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).condense.split).toList

    res.sameElements(Vec.condense(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  property("dropInitBlank") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).dropInitBlank.split).toList

    res.sameElements(Vec.dropInitBlank(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  property("dropFinalBlank") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).dropFinalBlank.split).toList

    res.sameElements(Vec.dropFinalBlank(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  property("dropDelims") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).dropDelims.split).toList

    res.sameElements(Vec.dropDelims(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  property("keepDelimsL") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).keepDelimsL.split).toList

    res.sameElements(Vec.keepDelimsL(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  property("keepDelimsR") = forAll { (v: Vector[Elt], delims: Set[Elt]) =>
    val res = Process.emitAll(v).pipe(Splitter.oneOf(delims.toVector).keepDelimsR.split).toList

    res.sameElements(Vec.keepDelimsR(Vec.whenElt[Elt](a => delims.exists(a === _))(v)))
  }

  /**
   * Provides splitting operations implemented on vectors for reference.
   */
  object Vec {
    def onSubsequence[A: Equal](seq: Vector[A])(
      vec: Vector[A]
    ): Vector[Vector[A]] = {
      def from(acc: Vector[Vector[A]], i: Int): Vector[Vector[A]] = {
        vec.indexOfSlice(seq, i) match {
          case -1 => acc :+ vec.slice(i, vec.size)
          case di => from(acc :+ vec.slice(i, di) :+ seq, di + seq.size)
        }
      }

      if (seq.nonEmpty) from(Vector.empty, 0) else {
        Vector.empty +: vec.flatMap(el => Vector(Vector.empty, Vector(el)))
      }
    }

    def whenElt[A](pred: A => Boolean)(vec: Vector[A]): Vector[Vector[A]] = {
      def from(acc: Vector[Vector[A]], i: Int): Vector[Vector[A]] = {
        vec.indexWhere(pred, i) match {
          case -1 => acc :+ vec.slice(i, vec.size)
          case di => from(acc :+ vec.slice(i, di) :+ Vector(vec(di)), di + 1)
        }
      }

      from(Vector.empty, 0)
    }

    /**
     * Put even-positioned elements on the right, odds on the left.
     */
    def separate[A](vec: Vector[A]): Vector[Either[A, A]] =
      vec.grouped(2).toVector.flatMap {
        case Vector(a, b) => Vector(Right(a), Left(b))
        case Vector(a) => Vector(Right(a))
      }


    def condense[A](split: Vector[Vector[A]]): Vector[Vector[A]] = {
      def go(vec: Vector[Either[Vector[A], Vector[A]]]): Vector[Vector[A]] =
        vec match {
          case Vector(Right(chunk)) => Vector(chunk)
          case Right(chunk) +: tail =>
            val (delims, rest) = tail.span(_.isLeft)

            chunk +: delims.flatMap(_.left.get) +: go(rest)
          case Vector() => Vector.empty
        }

      val separated = separate(split)

      go(
        separate(split).zipWithIndex.filter {
          case (Right(chunk), i) => i == 0 || i == separated.size - 1 || chunk.nonEmpty
          case _ => true
        }.map(_._1)
      )
    }

    def dropInitBlank[A](split: Vector[Vector[A]]): Vector[Vector[A]] = split match {
      case Vector() +: rest => rest
      case other => other
    }

    def dropFinalBlank[A](split: Vector[Vector[A]]): Vector[Vector[A]] = split match {
      case rest :+ Vector() => rest
      case other => other
    }

    def dropDelims[A](split: Vector[Vector[A]]): Vector[Vector[A]] =
      separate(split).collect {
        case Right(chunk) => chunk
      }

    def keepDelimsL[A](split: Vector[Vector[A]]): Vector[Vector[A]] = split match {
      case head +: tail => head +: tail.grouped(2).toVector.map(_.flatten)
    }

    def keepDelimsR[A](split: Vector[Vector[A]]): Vector[Vector[A]] =
      split.grouped(2).toVector.map(_.flatten)
  }
}
