package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.{\/-, -\/, Equal, Monoid}
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
import scalaz.concurrent.{Task, Strategy}
import scala.concurrent.SyncVar


object Process1Spec extends Properties("Process1") {
  import TestInstances._

  implicit val S = Strategy.DefaultStrategy

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
        "awaitOption" |: pi.awaitOption.toList === List(li.headOption)
        , s"buffer: $li ${pi.buffer(4).toList}" |: pi.buffer(4).toList === li
        , "chunk" |: Process(0, 1, 2, 3, 4).chunk(2).toList === List(Vector(0, 1), Vector(2, 3), Vector(4))
        , "chunkBy" |: emitAll("foo bar baz").chunkBy(_ != ' ').toList.map(_.mkString) ===  List("foo ", "bar ", "baz")
        , "chunkBy2" |: {
          val s = Process(3, 5, 4, 3, 1, 2, 6)
          (s.chunkBy2(_ < _).toList === List(Vector(3, 5), Vector(4), Vector(3), Vector(1, 2, 6)) &&
            s.chunkBy2(_ > _).toList === List(Vector(3), Vector(5, 4, 3, 1), Vector(2), Vector(6)))
        }
        , "collect" |: pi.collect(pf).toList === li.collect(pf)
        , "collectFirst" |: pi.collectFirst(pf).toList === li.collectFirst(pf).toList
        , "delete" |: pi.delete(_ == n).toList === li.diff(List(n))
        , "drop" |: pi.drop(n).toList === li.drop(n)
        , "dropLast" |: pi.dropLast.toList === li.dropRight(1)
        , "dropLastIf" |: {
           val pred = (_: Int) % 2 === 0
           val n = if (li.lastOption.map(pred).getOrElse(false)) 1 else 0
           pi.dropLastIf(pred).toList === li.dropRight(n) &&
           pi.dropLastIf(_ => false).toList === li
        }
        , "dropRight" |: pi.dropRight(n).toList === li.dropRight(n)
        , "dropWhile" |: pi.dropWhile(g).toList === li.dropWhile(g)
        , "exists" |: pi.exists(g).toList === List(li.exists(g))
        , s"feed: $li, ${process1.feed(li)(id[Int]).unemit._1.toList }" |: (li === process1.feed(li)(id[Int]).unemit._1.toList)
        , "feed-emit-first" |: ((List(1, 2, 3) ++ li) === process1.feed(li)(emitAll(List(1, 2, 3)) ++ id[Int]).unemit._1.toList)
        , "find" |: pi.find(_ % 2 === 0).toList === li.find(_ % 2 === 0).toList
        , "filter" |: pi.filter(g).toList === li.filter(g)
        , "filterBy2" |: pi.filterBy2(_ < _).toList.sliding(2).dropWhile(_.size < 2).forall(l => l(0) < l(1))
        , "fold" |: pi.fold(0)(_ + _).toList === List(li.fold(0)(_ + _))
        , "foldMap" |: pi.foldMap(_.toString).toList.lastOption.toList === List(li.map(_.toString).fold(sm.zero)(sm.append(_, _)))
        , "forall" |: pi.forall(g).toList === List(li.forall(g))
        , "id" |: ((pi |> id).toList === li) && ((id |> pi).toList === li)
        , "intersperse" |: pi.intersperse(0).toList === li.intersperse(0)
        , "last" |:  Process(0, 10).last.toList === List(10)
        , "lastOr" |: pi.lastOr(42).toList.head === li.lastOption.getOrElse(42)
        , "liftL"  |:  {
            val lifted = process1.liftL[Int,Int,Nothing](process1.id[Int].map( i=> i + 1) onComplete emit(Int.MinValue))
            pi.map(-\/(_)).pipe(lifted).toList == li.map(i => -\/(i + 1)) :+ -\/(Int.MinValue)
        }
        , "liftR"  |:  {
          val lifted = process1.liftR[Nothing,Int,Int](process1.id[Int].map( i=> i + 1) onComplete emit(Int.MinValue))
          pi.map(\/-(_)).pipe(lifted).toList == li.map(i => \/-(i + 1)) :+ \/-(Int.MinValue)
        }
        , "maximum" |: pi.maximum.toList === li.headOption.map(_ => List(li.max)).getOrElse(Nil)
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
        , "onComplete" |: Process(1,2,3).pipe(process1.id[Int] onComplete emit(4)).toList == List(1,2,3,4)
        , "once" |: pi.once.toList === li.headOption.toList
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
        , "stripNone" |: Process(None, Some(1), None, Some(2), None).pipe(stripNone).toList === List(1, 2)
        , "sum" |: pi.toSource.sum.runLastOr(0).timed(3000).run === li.sum
        , "prefixSums" |: pi.prefixSums.toList === li.scan(0)(_ + _)
        , "take" |: pi.take((n/10).abs).toList === li.take((n/10).abs)
        , "takeRight" |: pi.takeRight((n/10).abs).toList === li.takeRight((n/10).abs)
        , "takeWhile" |: pi.takeWhile(g).toList === li.takeWhile(g)
        , "terminated" |: Process(1, 2, 3).terminated.toList === List(Some(1), Some(2), Some(3), None)
        , "zipWithIndex" |: ps.zipWithIndex.toList === ls.zipWithIndex
        , "zipWithIndex[Double]" |: ps.zipWithIndex[Double].toList === ls.zipWithIndex.map { case (s, i) => (s, i.toDouble) }
      )

      examples.reduce(_ && _)
    } catch {
      case t : Throwable => t.printStackTrace(); throw t
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

  property("unchunk") = forAll { pi: Process0[List[Int]] =>
    pi.pipe(unchunk).toList == pi.toList.flatten
  }

  property("distinctConsecutive") = secure {
    Process[Int]().distinctConsecutive.toList === List.empty[Int] &&
      Process(1, 2, 3, 4).distinctConsecutive.toList === List(1, 2, 3, 4) &&
      Process(1, 1, 2, 2, 3, 3, 4, 3).distinctConsecutive.toList === List(1, 2, 3, 4, 3) &&
      Process("1", "2", "33", "44", "5", "66")
        .distinctConsecutiveBy(_.length).toList === List("1", "33", "5", "66")
  }

  property("drainLeading") = secure {
    val p = emit(1) ++ await1[Int]
    Process().pipe(p).toList === List(1) &&
      Process().pipe(drainLeading(p)).toList === List() &&
      Process(2).pipe(drainLeading(p)).toList === List(1, 2)
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

  property("sliding") = forAll { p: Process0[Int] =>
    val n = Gen.choose(1, 10).sample.get
    p.sliding(n).toList.map(_.toList) === p.toList.sliding(n).toList
  }

  property("splitOn") = secure {
    Process(0, 1, 2, 3, 4).splitOn(2).toList === List(Vector(0, 1), Vector(3, 4)) &&
      Process(2, 0, 1, 2).splitOn(2).toList === List(Vector(), Vector(0, 1), Vector()) &&
      Process(2, 2).splitOn(2).toList === List(Vector(), Vector(), Vector())
  }

  property("window") = secure {
    def window(n: Int) = range(0, 5).window(n).toList
    Process[Int]().window(2).toList === List(Vector.empty[Int]) &&
      window(1) === List(Vector(0), Vector(1), Vector(2), Vector(3), Vector(4), Vector()) &&
      window(2) === List(Vector(0, 1), Vector(1, 2), Vector(2, 3), Vector(3, 4), Vector(4)) &&
      window(3) === List(Vector(0, 1, 2), Vector(1, 2, 3), Vector(2, 3, 4), Vector(3, 4))
  }

  property("inner-cleanup") = secure {
    val p = Process.range(0,20).liftIO
    var called  = false
    ((p onComplete suspend{ called = true ; halt})
     .take(10).take(4).onComplete(emit(4)).runLog.run == Vector(0,1,2,3,4))
    .&&("cleanup was called" |: called)
  }

  property("zipWithPrevious") = secure {
    range(0, 0).zipWithPrevious.toList === List() &&
    range(0, 1).zipWithPrevious.toList === List((None, 0)) &&
    range(0, 3).zipWithPrevious.toList === List((None, 0), (Some(0), 1), (Some(1), 2))
  }

  property("zipWithNext") = secure {
    range(0, 0).zipWithNext.toList === List()
    range(0, 1).zipWithNext.toList === List((0, None)) &&
    range(0, 3).zipWithNext.toList === List((0, Some(1)), (1, Some(2)), (2, None))
  }

  property("zipWithPreviousAndNext") = secure {
    range(0, 0).zipWithPreviousAndNext.toList === List() &&
    range(0, 1).zipWithPreviousAndNext.toList === List((None, 0, None)) &&
    range(0, 3).zipWithPreviousAndNext.toList === List((None, 0, Some(1)), (Some(0), 1, Some(2)), (Some(1), 2, None))
  }
}
