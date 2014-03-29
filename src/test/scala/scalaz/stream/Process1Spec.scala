package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.Monoid
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.string._
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

import Process._
import process1._

import TestInstances._

object Process1Spec extends Properties("process1") {
  property("all") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
    val g = (x: Int) => x % 7 == 0
    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 == 0 => x}

    val sm = Monoid[String]

    ("id" |: {
      ((p |> id) === p) &&  ((id |> p) === p)
    }) &&
    ("filter" |: {
      p.toList.filter(g) === p.filter(g).toList
    }) &&
    ("take" |: {
      p.toList.take(n) === p.take(n).toList
    }) &&
    ("takeWhile" |: {
      p.toList.takeWhile(g) === p.takeWhile(g).toList
    }) &&
    ("drop" |: {
      p.toList.drop(n) === p.drop(n).toList
    }) &&
    ("dropLast" |: {
      p.dropLast.toList === p.toList.dropRight(1)
    }) &&
    ("dropLastIf" |: {
      val pred = (_: Int) % 2 == 0
      val pl = p.toList
      val n = if (pl.lastOption.map(pred).getOrElse(false)) 1 else 0
      p.dropLastIf(pred).toList === pl.dropRight(n) &&
      p.dropLastIf(_ => false).toList === p.toList
    }) &&
    ("dropWhile" |: {
      p.toList.dropWhile(g) === p.dropWhile(g).toList
    }) &&
    ("exists" |: {
      List(p.toList.exists(g)) === p.exists(g).toList
    }) &&
    ("forall" |: {
      List(p.toList.forall(g)) === p.forall(g).toList
    }) &&
    ("lastOr" |: {
      p.lastOr(42).toList === p.toList.lastOption.orElse(Some(42)).toList
    }) &&
    ("collect" |: {
      p.collect(pf).toList == p.toList.collect(pf)
    }) &&
    ("collectFirst" |: {
      p.collectFirst(pf).toList == p.toList.collectFirst(pf).toList
    }) &&
    ("find" |: {
      p.find(_ % 2 == 0).toList == p.toList.find(_ % 2 == 0).toList
    }) &&
    ("fold" |: {
      p.fold(0)(_ + _).toList == List(p.toList.fold(0)(_ + _))
    }) &&
    ("foldMap" |: {
      p.foldMap(_.toString).toList.lastOption.toList == List(p.toList.map(_.toString).fold(sm.zero)(sm.append(_,_)))
    }) &&
    ("intersperse" |: {
      p.intersperse(0).toList == p.toList.intersperse(0)
    }) &&
    ("maximum" |: {
      p.maximum.toList === p.toList.maximum.toList
    }) &&
    ("maximumBy" |: {
      // enable when switching to scalaz 7.1
      //p2.maximumBy(_.length).toList === p2.toList.maximumBy(_.length).toList
      true
    }) &&
    ("maximumOf" |: {
      p2.maximumOf(_.length).toList === p2.toList.map(_.length).maximum.toList
    }) &&
    ("minimum" |: {
      p.minimum.toList === p.toList.minimum.toList
    }) &&
    ("minimumBy" |: {
      // enable when switching to scalaz 7.1
      //p2.minimumBy(_.length).toList === p2.toList.minimumBy(_.length).toList
      true
    }) &&
    ("minimumOf" |: {
      p2.minimumOf(_.length).toList === p2.toList.map(_.length).minimum.toList
    }) &&
    ("reduce" |: {
      p.reduce(_ + _).toList == (if (p.toList.nonEmpty) List(p.toList.reduce(_ + _)) else List())
    }) &&
    ("scan" |: {
      p.toList.scan(0)(_ - _) ===
      p.toSource.scan(0)(_ - _).runLog.timed(3000).run.toList
    }) &&
    ("scan1" |: {
       p.toList.scan(0)(_ + _).tail ===
       p.toSource.scan1(_ + _).runLog.timed(3000).run.toList
    }) &&
    ("shiftRight" |: {
      p.shiftRight(1, 2).toList === List(1, 2) ++ p.toList
    }) &&
    ("splitWith" |: {
      p.splitWith(_ < n).toList.map(_.toList) === p.toList.splitWith(_ < n)
    }) &&
    ("sum" |: {
      p.toList.sum[Int] ===
      p.toSource.sum.runLastOr(0).timed(3000).run
    })
  }

  property("awaitOption") = secure {
    Process().awaitOption.toList == List(None) &&
    Process(1, 2).awaitOption.toList == List(Some(1))
  }

  property("chunk") = secure {
    Process(0, 1, 2, 3, 4).chunk(2).toList == List(Vector(0, 1), Vector(2, 3), Vector(4))
  }

  property("chunkBy") = secure {
    emitSeq("foo bar baz").chunkBy(_ != ' ').toList.map(_.mkString) ==
      List("foo ", "bar ", "baz")
  }

  property("chunkBy2") = secure {
    val s = Process(3, 5, 4, 3, 1, 2, 6)
    s.chunkBy2(_ < _).toList == List(Vector(3, 5), Vector(4), Vector(3), Vector(1, 2, 6)) &&
    s.chunkBy2(_ > _).toList == List(Vector(3), Vector(5, 4, 3, 1), Vector(2), Vector(6))
  }

  property("last") = secure {
    var i = 0
    Process.range(0,10).last.map(_ => i += 1).runLog.run
    i == 1
  }

  property("repartition") = secure {
    Process("Lore", "m ip", "sum dolo", "r sit amet").repartition(_.split(" ")).toList ==
      List("Lorem", "ipsum", "dolor", "sit", "amet") &&
    Process("hel", "l", "o Wor", "ld").repartition(_.grouped(2).toVector).toList ==
      List("he", "ll", "o ", "Wo", "rl", "d") &&
    Process(1, 2, 3, 4, 5).repartition(i => Vector(i, i)).toList ==
      List(1, 3, 6, 10, 15, 15) &&
    Process[String]().repartition(_ => Vector()).toList == List() &&
    Process("hello").repartition(_ => Vector()).toList == List()
  }

  property("repartition2") = secure {
    Process("he", "ll", "o").repartition2(s => (Some(s), None)).toList ===
      List("he", "ll", "o") &&
    Process("he", "ll", "o").repartition2(s => (None, Some(s))).toList ===
      List("hello") &&
    Process("he", "ll", "o").repartition2 {
      s => (Some(s.take(1)), Some(s.drop(1)))
    }.toList === List("h", "e", "l", "lo")
  }

  property("stripNone") = secure {
    Process(None, Some(1), None, Some(2), None).pipe(stripNone).toList === List(1, 2)
  }

  property("terminated") = secure {
    Process(1, 2, 3).terminated.toList == List(Some(1), Some(2), Some(3), None)
  }

  property("window") = secure {
    def window(n: Int) = Process.range(0, 5).window(n).runLog.run.toList
    window(1) == List(Vector(0), Vector(1), Vector(2), Vector(3), Vector(4), Vector()) &&
    window(2) == List(Vector(0, 1), Vector(1, 2), Vector(2, 3), Vector(3, 4), Vector(4)) &&
    window(3) == List(Vector(0, 1, 2), Vector(1, 2, 3), Vector(2, 3, 4), Vector(3, 4))
  }
}
