package scalaz.stream2

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.{Equal, Monoid}
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.vector._
import scalaz.std.string._
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

import Process._
import process1._

import TestInstances._
import scalaz.concurrent.Strategy


object Process1Spec extends Properties("Process1") {
  import TestInstances._

  implicit val S = Strategy.DefaultStrategy

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
  //  object Subtyping {
  //    def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
  //    def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
  //  }



  property("basic") = forAll { (pi: Process0[Int], ps: Process0[String], n: Int) =>
    val li = pi.toList
    val ls = ps.toList

    val g = (x: Int) => x % 7 === 0
    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 === 0 => x}
    val sm = Monoid[String]
    //
    //  println("##########"*10 + p)
    //  println("P1 " + p.toList.flatMap(f).size)
    //  println("P2 " + p.flatMap(f andThen Process.emitAll).toList.size )

    try {
      val examples = Seq(
        s"feed: $li, ${process1.feed(li)(id[Int]).unemit._1.toList }" |: (li === process1.feed(li)(id[Int]).unemit._1.toList)
        , "feed-emit-first" |: ((List(1, 2, 3) ++ li) === process1.feed(li)(emitAll(List(1, 2, 3)) ++ id[Int]).unemit._1.toList)
        , s"buffer: $li ${pi.buffer(4).toList}" |: pi.buffer(4).toList === li
        , "collect" |: pi.collect(pf).toList === li.collect(pf)
        , "collectFirst" |: pi.collectFirst(pf).toList === li.collectFirst(pf).toList
        , "drop" |: pi.drop(n).toList === li.drop(n)
        , "dropLast" |: pi.dropLast.toList === li.dropRight(1)
        , "dropLastIf" |: {
           val pred = (_: Int) % 2 === 0
           val n = if (li.lastOption.map(pred).getOrElse(false)) 1 else 0
           pi.dropLastIf(pred).toList === li.dropRight(n) &&
           pi.dropLastIf(_ => false).toList === li
        }
        , "dropWhile" |: pi.dropWhile(g).toList === li.dropWhile(g)
        , "exists" |: pi.exists(g).toList === List(li.exists(g))
        , "find" |: pi.find(_ % 2 === 0).toList === li.find(_ % 2 === 0).toList
        , "filter" |: pi.filter(g).toList === li.filter(g)
        , "fold" |: pi.fold(0)(_ + _).toList === List(li.fold(0)(_ + _))
        , "foldMap" |: pi.foldMap(_.toString).toList.lastOption.toList === List(li.map(_.toString).fold(sm.zero)(sm.append(_, _)))
        , "forall" |: pi.forall(g).toList === List(li.forall(g))
        , "id" |: ((pi |> id) === pi) && ((id |> pi) === pi)
        , "intersperse" |: pi.intersperse(0).toList === li.intersperse(0)
        , "lastOr" |: pi.lastOr(42).toList.head === li.lastOption.getOrElse(42)
        , "maximum" |: pi.maximum.toList === li.maximum.toList
        , "maximumBy" |: {
          // enable when switching to scalaz 7.1
          //ps.maximumBy(_.length).toList === ls.maximumBy(_.length).toList
          true
        }
        , "maximumOf" |: ps.maximumOf(_.length).toList === ls.map(_.length).maximum.toList
        , "minimum" |: pi.minimum.toList === li.minimum.toList
        , "minimumBy" |: {
          // enable when switching to scalaz 7.1
          //ps.minimumBy(_.length).toList === ls.minimumBy(_.length).toList
          true
        }
        , "minimumOf" |: ps.minimumOf(_.length).toList === ls.map(_.length).minimum.toList
        , "reduce" |: pi.reduce(_ + _).toList === (if (li.nonEmpty) List(li.reduce(_ + _)) else List())
        , "scan" |: {
          li.scan(0)(_ - _) ===
            pi.toSource.scan(0)(_ - _).runLog.timed(3000).run.toList
        }
        , "scan1" |: {
          li.scan(0)(_ + _).tail ===
            pi.toSource.scan1(_ + _).runLog.timed(3000).run.toList
        }
        , "shiftRight" |: pi.shiftRight(1, 2).toList === List(1, 2) ++ li
        , "splitWith" |: pi.splitWith(_ < n).toList.map(_.toList) === li.splitWith(_ < n)
        , "sum" |: pi.toSource.sum.runLastOr(0).timed(3000).run === li.sum
        , "take" |: pi.take(n).toList === li.take(n)
        , "takeWhile" |: pi.takeWhile(g).toList === li.takeWhile(g)
      )

      examples.reduce(_ && _)
    } catch {
      case t : Throwable => t.printStackTrace(); throw t
    }



  }


}