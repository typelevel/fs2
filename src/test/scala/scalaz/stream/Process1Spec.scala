package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.Monoid
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

object Process1Spec extends Properties("process1") {
  property("all") = forAll { (pi: Process0[Int], ps: Process0[String], n: Int) =>
    val li = pi.toList
    val ls = ps.toList

    val g = (x: Int) => x % 7 === 0
    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 === 0 => x}
    val sm = Monoid[String]

    ("buffer" |: {
      pi.buffer(4).toList === li
    }) &&
    ("collect" |: {
      pi.collect(pf).toList === li.collect(pf)
    }) &&
    ("collectFirst" |: {
      pi.collectFirst(pf).toList === li.collectFirst(pf).toList
    }) &&
    ("drop" |: {
      pi.drop(n).toList === li.drop(n)
    }) &&
    ("dropLast" |: {
      pi.dropLast.toList === li.dropRight(1)
    }) &&
    ("dropLastIf" |: {
      val pred = (_: Int) % 2 === 0
      val n = if (li.lastOption.map(pred).getOrElse(false)) 1 else 0
      pi.dropLastIf(pred).toList === li.dropRight(n) &&
      pi.dropLastIf(_ => false).toList === li
    }) &&
    ("dropWhile" |: {
      pi.dropWhile(g).toList === li.dropWhile(g)
    }) &&
    ("exists" |: {
      pi.exists(g).toList === List(li.exists(g))
    }) &&
    ("find" |: {
      pi.find(_ % 2 === 0).toList === li.find(_ % 2 === 0).toList
    }) &&
    ("filter" |: {
      pi.filter(g).toList === li.filter(g)
    }) &&
    ("fold" |: {
      pi.fold(0)(_ + _).toList === List(li.fold(0)(_ + _))
    }) &&
    ("foldMap" |: {
      pi.foldMap(_.toString).toList.lastOption.toList === List(li.map(_.toString).fold(sm.zero)(sm.append(_,_)))
    }) &&
    ("forall" |: {
      pi.forall(g).toList === List(li.forall(g))
    }) &&
    ("id" |: {
      ((pi |> id) === pi) && ((id |> pi) === pi)
    }) &&
    ("intersperse" |: {
      pi.intersperse(0).toList === li.intersperse(0)
    }) &&
    ("lastOr" |: {
      pi.lastOr(42).toList.head === li.lastOption.getOrElse(42)
    }) &&
    ("maximum" |: {
      pi.maximum.toList === li.maximum.toList
    }) &&
    ("maximumBy" |: {
      // enable when switching to scalaz 7.1
      //ps.maximumBy(_.length).toList === ls.maximumBy(_.length).toList
      true
    }) &&
    ("maximumOf" |: {
      ps.maximumOf(_.length).toList === ls.map(_.length).maximum.toList
    }) &&
    ("minimum" |: {
      pi.minimum.toList === li.minimum.toList
    }) &&
    ("minimumBy" |: {
      // enable when switching to scalaz 7.1
      //ps.minimumBy(_.length).toList === ls.minimumBy(_.length).toList
      true
    }) &&
    ("minimumOf" |: {
      ps.minimumOf(_.length).toList === ls.map(_.length).minimum.toList
    }) &&
    ("reduce" |: {
      pi.reduce(_ + _).toList === (if (li.nonEmpty) List(li.reduce(_ + _)) else List())
    }) &&
    ("scan" |: {
      li.scan(0)(_ - _) ===
      pi.toSource.scan(0)(_ - _).runLog.timed(3000).run.toList
    }) &&
    ("scan1" |: {
      li.scan(0)(_ + _).tail ===
      pi.toSource.scan1(_ + _).runLog.timed(3000).run.toList
    }) &&
    ("shiftRight" |: {
      pi.shiftRight(1, 2).toList === List(1, 2) ++ li
    }) &&
    ("splitWith" |: {
      pi.splitWith(_ < n).toList.map(_.toList) === li.splitWith(_ < n)
    }) &&
    ("sum" |: {
      pi.toList.sum[Int] ===
      pi.toSource.pipe(process1.sum).runLast.timed(3000).run.get
    }) &&
    ("prefixSums" |: {
      pi.toList.scan(0)(_ + _) ===
      pi.toSource.pipe(process1.prefixSums).runLog.run.toList
    }) &&
    ("take" |: {
      pi.take(n).toList === li.take(n)
    }) &&
    ("takeWhile" |: {
      pi.takeWhile(g).toList === li.takeWhile(g)
    }) &&
    ("zipWithIndex" |: {
      ps.zipWithIndex.toList === ls.zipWithIndex
    }) &&
    ("zipWithIndex[Double]" |: {
      ps.zipWithIndex[Double].toList === ls.zipWithIndex.map { case (s, i) => (s, i.toDouble) }
    })
  }

  property("awaitOption") = secure {
    Process().awaitOption.toList == List(None) &&
    Process(1, 2).awaitOption.toList == List(Some(1))
  }

  property("chunk") = secure {
    Process(0, 1, 2, 3, 4).chunk(2).toList === List(Vector(0, 1), Vector(2, 3), Vector(4))
  }

  property("chunkBy") = secure {
    emitSeq("foo bar baz").chunkBy(_ != ' ').toList.map(_.mkString) ==
      List("foo ", "bar ", "baz")
  }

  property("chunkBy2") = secure {
    val s = Process(3, 5, 4, 3, 1, 2, 6)
    s.chunkBy2(_ < _).toList === List(Vector(3, 5), Vector(4), Vector(3), Vector(1, 2, 6)) &&
    s.chunkBy2(_ > _).toList === List(Vector(3), Vector(5, 4, 3, 1), Vector(2), Vector(6))
  }

  property("unchunk") = forAll { pi: Process0[List[Int]] =>
    pi.pipe(unchunk).toList == pi.toList.flatten
  }

  property("last") = secure {
    var i = 0
    Process.range(0, 10).last.map(_ => i += 1).runLog.run
    i === 1
  }

  property("repartition") = secure {
    Process("Lore", "m ip", "sum dolo", "r sit amet").repartition(_.split(" ")).toList ==
      List("Lorem", "ipsum", "dolor", "sit", "amet") &&
    Process("hel", "l", "o Wor", "ld").repartition(_.grouped(2).toVector).toList ==
      List("he", "ll", "o ", "Wo", "rl", "d") &&
    Process(1, 2, 3, 4, 5).repartition(i => Vector(i, i)).toList ==
      List(1, 3, 6, 10, 15, 15) &&
    Process[String]().repartition(_ => Vector()).toList.isEmpty &&
    Process("hello").repartition(_ => Vector()).toList.isEmpty
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

  property("splitOn") = secure {
    Process(0, 1, 2, 3, 4).splitOn(2).toList === List(Vector(0, 1), Vector(3, 4)) &&
    Process(2, 0, 1, 2).splitOn(2).toList === List(Vector(), Vector(0, 1), Vector()) &&
    Process(2, 2).splitOn(2).toList === List(Vector(), Vector(), Vector())
  }

  property("stripNone") = secure {
    Process(None, Some(1), None, Some(2), None).pipe(stripNone).toList === List(1, 2)
  }

  property("terminated") = secure {
    Process(1, 2, 3).terminated.toList === List(Some(1), Some(2), Some(3), None)
  }

  property("window") = secure {
    def window(n: Int) = Process.range(0, 5).window(n).runLog.run.toList
    window(1) === List(Vector(0), Vector(1), Vector(2), Vector(3), Vector(4), Vector()) &&
    window(2) === List(Vector(0, 1), Vector(1, 2), Vector(2, 3), Vector(3, 4), Vector(4)) &&
    window(3) === List(Vector(0, 1, 2), Vector(1, 2, 3), Vector(2, 3, 4), Vector(3, 4))
  }
}
