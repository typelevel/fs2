package scalaz.stream2

import org.scalacheck.Properties
import scalaz.concurrent.Strategy

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

/**
 * Created by pach on 09/04/14.
 */
object TeeSpec extends Properties("Tee") {

  import TestInstances._

  implicit val S = Strategy.DefaultStrategy

  property("basic") = forAll { (pi: Process0[Int], ps: Process0[String], n: Int) =>
    val li = pi.toList
    val ls = ps.toList

    val g = (x: Int) => x % 7 === 0
    val pf: PartialFunction[Int, Int] = {case x: Int if x % 2 === 0 => x }
    val sm = Monoid[String]


    println("##########"*10 )
    println("li" + li)
    println("ls" + ls)
    println("P1 " + li.toList.zip(ls.toList))
    println("P2 " + pi.zip(ps).toList )

    try {



      val examples = Seq(
        s"zip: $li | $ls " |: li.toList.zip(ls.toList) === pi.zip(ps).toList
      )

      examples.reduce(_ && _)
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
  }

}
