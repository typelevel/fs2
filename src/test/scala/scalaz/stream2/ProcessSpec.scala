package scalaz.stream2

import org.scalacheck.Prop._

import scalaz._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.string._

import org.scalacheck.{Arbitrary, Properties}
import scalaz.concurrent.Strategy
import Util._
import scalaz.stream2.process1._
import scalaz.stream2.Process.Kill
import Process._

object ProcessSpec extends Properties("Process") {

  val boom = new java.lang.Exception("reactive...boom!")

  implicit val S = Strategy.DefaultStrategy

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
  //  object Subtyping {
  //    def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
  //    def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
  //  }


  implicit def EqualProcess[A: Equal]: Equal[Process0[A]] = new Equal[Process0[A]] {
    def equal(a: Process0[A], b: Process0[A]): Boolean =
      a.toList === b.toList
  }
  implicit def ArbProcess0[A: Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(Arbitrary.arbitrary[List[A]].map(a => Process(a: _*)))


//  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
//    val f = (x: Int) => List.range(1, x.min(100))
//    val g = (x: Int) => x % 7 == 0
//    val pf: PartialFunction[Int, Int] = {case x: Int if x % 2 == 0 => x }
//
//    val sm = Monoid[String]
////
////  println("##########"*10 + p)
////  println("P1 " + p.toList.flatMap(f).size)
////  println("P2 " + p.flatMap(f andThen Process.emitAll).toList.size )
//
//  try {
//    val examples = Seq(
//      //  "map" |:  (p.toList.map(_ + 1) === p.map(_ + 1).toList)
//      //   "map-pipe" |: (p.map(_ + 1) === p.pipe(lift(_ + 1)))
//     //   , "flatMap" |:   (p.toList.flatMap(f) === p.flatMap(f andThen Process.emitAll).toList)
//    )
//
//    //examples.reduce(_ && _)
//    false
//  } catch {
//    case t : Throwable => t.printStackTrace(); throw t
//  }
//
//
//
//  }


  property("kill") = secure {

    ("repeated-emit" |: emit(1).toSource.repeat.kill.runLog.run == List()) //&&
      //("repeated-emit" |: emit(1).toSource.killBy(boom).runLog.run == List())

  }


}
