package scalaz.stream

import scalaz.Equal
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._

import org.scalacheck._
import Prop._

object Process1Spec extends Properties("Process1") {
  
  import Process._
  import process1._

  implicit def EqualProcess[A:Equal]: Equal[Process0[A]] = new Equal[Process0[A]] {
    def equal(a: Process0[A], b: Process0[A]): Boolean = 
      a.toList == b.toList
  }
  implicit def ArbProcess0[A:Arbitrary]: Arbitrary[Process0[A]] = 
    Arbitrary(Arbitrary.arbitrary[List[A]].map(a => Process(a: _*)))

  property("basic processes") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) => 
    val f = (x: Int) => List.range(1, x.min(100))
    val g = (x: Int) => x % 7 == 0
    ("id" |: { 
      ((p |> id) === p) &&  ((id |> p) === p)
    }) &&
    ("map" |: {
      (p.toList.map(_ + 1) === p.map(_ + 1).toList) && 
      (p.map(_ + 1) === p.pipe(lift(_ + 1)))
    }) &&
    ("flatMap" |: {
      (p.toList.flatMap(f) === p.flatMap(f andThen Process.emitAll).toList)
    }) && 
    ("filter" |: {
      (p.toList.filter(g) === p.filter(g).toList)
    }) && 
    ("take" |: {
      (p.toList.take(n) === p.take(n).toList)
    }) && 
    ("takeWhile" |: {
      (p.toList.takeWhile(g) === p.takeWhile(g).toList)
    }) && 
    ("drop" |: {
      (p.toList.drop(n) === p.drop(n).toList)
    }) &&
    ("dropWhile" |: {
      (p.toList.dropWhile(g) === p.dropWhile(g).toList)
    }) && 
    ("zip" |: {
      (p.toList.zip(p2.toList) === p.zip(p2).toList)
    }) && 
    ("yip" |: {
      val l = p.toList.zip(p2.toList)
      val r = p.toSource.yip(p2.toSource).collect.run.toList
      (l === r)
    })
  }
}

