package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.{\/-, -\/, Monoid, State}
import scalaz.concurrent.Task
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.option._
import scalaz.std.vector._
import scalaz.std.tuple._
import scalaz.std.string._
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

import Process._
import process1._

import TestInstances._

class Process1Spec extends Properties("Process1") {

  property("basic") = forAll { (pi: Process0[Int], ps: Process0[String]) =>
    val li = pi.toList
    val ls = ps.toList

    val i = Gen.choose(-1, 20).sample.get
    val j = i.abs + 1
    val f = (_: Int) % j === 0
    val pf: PartialFunction[Int, Int] = { case x if x % 2 === 0 => x }
    val sm = Monoid[String]

    try {
      val examples = Seq(
          "awaitOption" |: pi.awaitOption.toList === List(li.headOption)
        , s"buffer: $li ${pi.buffer(4).toList}" |: pi.buffer(4).toList === li
        , "collect" |: pi.collect(pf).toList === li.collect(pf)
        , "collectFirst" |: pi.collectFirst(pf).toList === li.collectFirst(pf).toList
        , "contramap" |: ps.map(_.length).sum === ps.pipe(sum[Int].contramap(_.length))
        , "delete" |: pi.delete(_ === i).toList === li.diff(List(i))
        , "drop" |: pi.drop(i).toList === li.drop(i)
        , "dropLast" |: pi.dropLast.toList === li.dropRight(1)
        , "dropLastIf" |: {
           val k = if (li.lastOption.exists(f)) 1 else 0
           pi.dropLastIf(f).toList === li.dropRight(k) &&
           pi.dropLastIf(_ => false).toList === li
        }
        , "dropRight" |: pi.dropRight(i).toList === li.dropRight(i)
        , "dropWhile" |: pi.dropWhile(f).toList === li.dropWhile(f)
        , "exists" |: pi.exists(f).toList === List(li.exists(f))
        , s"feed: $li, ${feed(li)(id[Int]).unemit._1.toList }" |: (li === feed(li)(id[Int]).unemit._1.toList)
        , "feed-emit-first" |: ((List(1, 2, 3) ++ li) === feed(li)(emitAll(List(1, 2, 3)) ++ id[Int]).unemit._1.toList)
        , "find" |: pi.find(f).toList === li.find(f).toList
        , "filter" |: pi.filter(f).toList === li.filter(f)
        , "filterBy2" |: pi.filterBy2(_ < _).toList.sliding(2).dropWhile(_.size < 2).forall(l => l(0) < l(1))
        , "fold" |: pi.fold(0)(_ + _).toList === List(li.fold(0)(_ + _))
        , "foldMap" |: pi.foldMap(_.toString).toList.lastOption.toList === List(li.map(_.toString).fold(sm.zero)(sm.append(_, _)))
        , "forall" |: pi.forall(f).toList === List(li.forall(f))
        , "id" |: ((pi |> id).toList === li) && ((id |> pi).disconnect(Cause.Kill).toList === li)
        , "intersperse" |: pi.intersperse(i).toList === li.intersperse(i)
        , "last" |: pi.last.toList.headOption === li.lastOption
        , "lastOr" |: pi.lastOr(i).toList.head === li.lastOption.getOrElse(i)
        , "liftFirst"  |:  {
            val lifted = process1.liftFirst[Int,Int,Int](b => Some(b))(process1.id[Int].map(i => i + 1) onComplete emit(Int.MinValue))
            pi.map(i => (i, i + 1)).pipe(lifted).toList == li.map(i => (i + 1, i + 1)) :+ ((Int.MinValue, Int.MinValue))
        }, "liftSecond"  |:  {
            val lifted = process1.liftSecond[Int,Int,Int](b => Some(b))(process1.id[Int].map(i => i + 1) onComplete emit(Int.MinValue))
            pi.map(i => (i + 1, i)).pipe(lifted).toList == li.map(i => (i + 1, i + 1)) :+ ((Int.MinValue, Int.MinValue))
        }, "liftL"  |:  {
            val lifted = process1.liftL[Int,Int,Nothing](process1.id[Int].map( i=> i + 1) onComplete emit(Int.MinValue))
            pi.map(-\/(_)).pipe(lifted).toList == li.map(i => -\/(i + 1)) :+ -\/(Int.MinValue)
        }
        , "liftR"  |:  {
          val lifted = process1.liftR[Nothing,Int,Int](process1.id[Int].map( i=> i + 1) onComplete emit(Int.MinValue))
          pi.map(\/-(_)).pipe(lifted).toList == li.map(i => \/-(i + 1)) :+ \/-(Int.MinValue)
        }
        , "mapAccumulate" |: {
          val r = pi.mapAccumulate(0)((s, i) => (s + i, f(i))).toList
          r.map(_._1) === li.scan(0)(_ + _).tail && r.map(_._2) === li.map(f)
        }
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
        , "onComplete" |: Process(1,2,3).pipe(id[Int] onComplete emit(4)).toList == List(1,2,3,4)
        , "once" |: pi.once.toList === li.headOption.toList
        , "reduce" |: pi.reduce(_ + _).toList === (if (li.nonEmpty) List(li.reduce(_ + _)) else List())
        , "scan" |: pi.scan(0)(_ - _).toList === li.scan(0)(_ - _)
        , "scan1" |: pi.scan1(_ + _).toList === li.scan(0)(_ + _).tail
        , "shiftRight" |: pi.shiftRight(1, 2).toList === List(1, 2) ++ li
        , "sliding" |: pi.sliding(j).toList.map(_.toList) === li.sliding(j).toList
        , "splitWith" |: pi.splitWith(_ < i).toList.map(_.toList) === li.splitWith(_ < i).map(_.toList)
        , "sum" |: pi.sum.toList.headOption.getOrElse(0) === li.sum
        , "prefixSums" |: pi.prefixSums.toList === li.scan(0)(_ + _)
        , "tail" |: pi.tail.toList === li.drop(1)
        , "take" |: pi.take(i).toList === li.take(i)
        , "takeRight" |: pi.takeRight(i).toList === li.takeRight(i)
        , "takeWhile" |: pi.takeWhile(f).toList === li.takeWhile(f)
        , "zipWithIndex" |: pi.zipWithIndex.toList === li.zipWithIndex
        , "zipWithIndex[Double]" |: pi.zipWithIndex[Double].toList === li.zipWithIndex.map { case (s, i) => (s, i.toDouble) }
      )

      examples.reduce(_ && _)
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
  }

  property("apply-does-not-silently-fail") = forAll { xs: List[Int] =>
    val err = 1 #:: ((throw new scala.Exception("FAIL")):Stream[Int])
    try {
      Process.emitAll(err)(xs)
      false
    } catch {
      case e: scala.Exception => true
      case _: Throwable => false
    }
  }

  property("inner-cleanup") = protect {
    val p = Process.range(0,20).toSource
    var called  = false
    ((p onComplete suspend{ called = true ; halt})
      .take(10).take(4).onComplete(emit(4)).runLog.run == Vector(0,1,2,3,4))
      .&&("cleanup was called" |: called)
  }

  property("chunk") = protect {
    Process(0, 1, 2, 3, 4).chunk(2).toList === List(Vector(0, 1), Vector(2, 3), Vector(4))
  }

  property("chunkBy") = protect {
    emitAll("foo bar baz").chunkBy(_ != ' ').toList.map(_.mkString) === List("foo ", "bar ", "baz")
  }

  property("chunkBy2") = protect {
    val p = Process(3, 5, 4, 3, 1, 2, 6)
    p.chunkBy2(_ < _).toList === List(Vector(3, 5), Vector(4), Vector(3), Vector(1, 2, 6)) &&
    p.chunkBy2(_ > _).toList === List(Vector(3), Vector(5, 4, 3, 1), Vector(2), Vector(6))
  }

  property("distinctConsecutive") = protect {
    Process[Int]().distinctConsecutive.toList.isEmpty &&
    Process(1, 2, 3, 4).distinctConsecutive.toList === List(1, 2, 3, 4) &&
    Process(1, 1, 2, 2, 3, 3, 4, 3).distinctConsecutive.toList === List(1, 2, 3, 4, 3) &&
    Process("1", "2", "33", "44", "5", "66").distinctConsecutiveBy(_.length).toList ===
      List("1", "33", "5", "66")
  }

  property("drainLeading") = protect {
    val p = emit(1) ++ await1[Int]
    Process().pipe(p).toList === List(1) &&
    Process().pipe(drainLeading(p)).toList.isEmpty &&
    Process(2).pipe(drainLeading(p)).toList === List(1, 2)
  }

  property("stateScan") = forAll { (xs: List[Int], init: Int, f: (Int, Int) => Int) =>
    val results = emitAll(xs) pipe stateScan(init) { i =>
      for {
        accum <- State.get[Int]
        total = f(accum, i)
        _ <- State.put(total)
      } yield total
    }

    results.toList === (xs.scan(init)(f) drop 1)
  }

  property("repartition") = protect {
    Process("Lore", "m ip", "sum dolo", "r sit amet").repartition(_.split(" ")).toList ===
      List("Lorem", "ipsum", "dolor", "sit", "amet") &&
    Process("hel", "l", "o Wor", "ld").repartition(_.grouped(2).toVector).toList ===
      List("he", "ll", "o ", "Wo", "rl", "d") &&
    Process(1, 2, 3, 4, 5).repartition(i => Vector(i, i)).toList ===
      List(1, 3, 6, 10, 15, 15) &&
    Process[String]().repartition(_ => Vector()).toList.isEmpty &&
    Process("hello").repartition(_ => Vector()).toList.isEmpty
  }

  property("repartition2") = protect {
    Process("he", "ll", "o").repartition2(s => (Some(s), None)).toList ===
      List("he", "ll", "o") &&
    Process("he", "ll", "o").repartition2(s => (None, Some(s))).toList ===
      List("hello") &&
    Process("he", "ll", "o").repartition2(s => (Some(s.take(1)), Some(s.drop(1)))).toList ===
      List("h", "e", "l", "lo") &&
    Process("he", "ll", "o").repartition2(_ => (None, None)).toList.isEmpty
  }

  property("splitOn") = protect {
    Process(0, 1, 2, 3, 4).splitOn(2).toList === List(Vector(0, 1), Vector(3, 4)) &&
    Process(2, 0, 1, 2).splitOn(2).toList === List(Vector(), Vector(0, 1), Vector()) &&
    Process(2, 2).splitOn(2).toList === List(Vector(), Vector(), Vector())
  }

  property("stripNone") = protect {
    Process(None, Some(1), None, Some(2), None).pipe(stripNone).toList === List(1, 2)
  }

  property("terminated") = protect {
    Process[Int]().terminated.toList === List(None) &&
    Process(1, 2, 3).terminated.toList === List(Some(1), Some(2), Some(3), None)
  }

  property("unchunk") = forAll { pi: Process0[List[Int]] =>
    pi.pipe(unchunk).toList === pi.toList.flatten
  }

  property("zipWithPrevious") = protect {
    range(0, 0).zipWithPrevious.toList.isEmpty &&
    range(0, 1).zipWithPrevious.toList === List((None, 0)) &&
    range(0, 3).zipWithPrevious.toList === List((None, 0), (Some(0), 1), (Some(1), 2))
  }

  property("zipWithNext") = protect {
    range(0, 0).zipWithNext.toList.isEmpty &&
    range(0, 1).zipWithNext.toList === List((0, None)) &&
    range(0, 3).zipWithNext.toList === List((0, Some(1)), (1, Some(2)), (2, None))
  }

  property("zipWithPreviousAndNext") = protect {
    range(0, 0).zipWithPreviousAndNext.toList.isEmpty &&
    range(0, 1).zipWithPreviousAndNext.toList === List((None, 0, None)) &&
    range(0, 3).zipWithPreviousAndNext.toList === List((None, 0, Some(1)), (Some(0), 1, Some(2)), (Some(1), 2, None))
  }
}
