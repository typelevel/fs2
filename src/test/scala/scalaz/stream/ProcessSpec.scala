package scalaz.stream

import scalaz.{Monoid, Equal, Nondeterminism}
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.string._

import org.scalacheck._
import Prop._
import Arbitrary.arbitrary

object ProcessSpec extends Properties("Process1") {

  import Process._
  import process1._

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
  object Subtyping {
    def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
    def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
  }


  implicit def EqualProcess[A:Equal]: Equal[Process0[A]] = new Equal[Process0[A]] {
    def equal(a: Process0[A], b: Process0[A]): Boolean =
      a.toList == b.toList
  }
  implicit def ArbProcess0[A:Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(Arbitrary.arbitrary[List[A]].map(a => Process(a: _*)))

  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
    val f = (x: Int) => List.range(1, x.min(100))
    val g = (x: Int) => x % 7 == 0
    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 == 0 => x}

    val sm = Monoid[String]

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
    ("exists" |: {
      (List(p.toList.exists(g)) === p.exists(g).toList)
    }) &&
    ("forall" |: {
      (List(p.toList.forall(g)) === p.forall(g).toList)
    }) &&
    ("zip" |: {
      (p.toList.zip(p2.toList) === p.zip(p2).toList)
    }) &&
    ("yip" |: {
      val l = p.toList.zip(p2.toList)
      val r = p.toSource.yip(p2.toSource).runLog.run.toList
      (l === r)
    }) &&
    ("scan" |: {
      p.toList.scan(0)(_ - _) ===
      p.toSource.scan(0)(_ - _).runLog.run.toList
    }) &&
    ("scan1" |: {
       p.toList.scan(0)(_ + _).tail ===
       p.toSource.scan1(_ + _).runLog.run.toList
    }) &&
    ("sum" |: {
      p.toList.sum[Int] ===
      p.toSource.pipe(process1.sum).runLastOr(0).run
    }) &&
    ("intersperse" |: {
      p.intersperse(0).toList == p.toList.intersperse(0)
    }) &&
    ("collect" |: {
      p.collect(pf).toList == p.toList.collect(pf)
    }) &&
    ("fold" |: {
      p.fold(0)(_ + _).toList == List(p.toList.fold(0)(_ + _))
    }) &&
    ("foldMap" |: {
      p.foldMap(_.toString).toList.lastOption.toList == List(p.toList.map(_.toString).fold(sm.zero)(sm.append(_,_)))
    }) &&
    ("reduce" |: {
      (p.reduce(_ + _).toList == (if (p.toList.nonEmpty) List(p.toList.reduce(_ + _)) else List()))
    }) &&
    ("find" |: {
       (p.find(_ % 2 == 0).toList == p.toList.find(_ % 2 == 0).toList)
    })
  }

   property("fill") = forAll(Gen.choose(0,30).map2(Gen.choose(0,50))((_,_))) {
    case (n,chunkSize) => Process.fill(n)(42, chunkSize).runLog.run.toList == List.fill(n)(42)
  }

  import scalaz.concurrent.Task

  property("enqueue") = secure {
    val tasks = Process.range(0,1000).map(i => Task { Thread.sleep(1); 1 })
    tasks.sequence(50).pipe(processes.sum[Int].last).runLog.run.head == 1000 &&
    tasks.gather(50).pipe(processes.sum[Int].last).runLog.run.head == 1000
  }

  // ensure that zipping terminates when the smaller stream runs out
  property("zip one side infinite") = secure {
    val ones = Process.eval(Task.now(1)).repeat
    val p = Process(1,2,3)
    ones.zip(p).runLog.run == IndexedSeq(1 -> 1, 1 -> 2, 1 -> 3) &&
    p.zip(ones).runLog.run == IndexedSeq(1 -> 1, 2 -> 1, 3 -> 1)
  }

  property("merge") = secure {
    import concurrent.duration._
    val sleepsL = Process.awakeEvery(1 seconds).take(3)
    val sleepsR = Process.awakeEvery(100 milliseconds).take(30)
    val sleeps = sleepsL merge sleepsR
    val p = sleeps.toTask
    val tasks = List.fill(10)(p.timed(500).attemptRun)
    tasks.forall(_.isRight)
  }

  property("forwardFill") = secure {
    import concurrent.duration._
    val t2 = Process.awakeEvery(2 seconds).forwardFill.zip {
             Process.awakeEvery(100 milliseconds).take(100)
           }.run.timed(15000).run
    true
  }

  property("range") = secure {
    Process.range(0, 100).runLog.run == IndexedSeq.range(0, 100) &&
    Process.range(0, 1).runLog.run == IndexedSeq.range(0, 1) &&
    Process.range(0, 0).runLog.run == IndexedSeq.range(0, 0)
  }

  property("ranges") = forAll(Gen.choose(1, 101)) { size =>
    Process.ranges(0, 100, size).flatMap { case (i,j) => emitSeq(i until j) }.runLog.run ==
    IndexedSeq.range(0, 100)
  }

  property("liftL") = secure {
    import scalaz.\/._
    val s = Process.range(0, 100000)
    val p = s.map(left) pipe process1.id[Int].liftL
    true
  }

  property("feedL") = secure {
    val w = wye.feedL(List.fill(10)(1))(process1.id)
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  property("feedR") = secure {
    val w = wye.feedR(List.fill(10)(1))(wye.merge[Int])
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process.constant(1).take(1)
    s.wye(s)(w).runLog.run.map(_.fold(identity, identity)).toList == List(1,1)
  }

  property("last") = secure {
    var i = 0
    Process.range(0,10).last.map(_ => i += 1).runLog.run
    i =? 1
  }

  property("state") = secure {
    val s = Process.state((0, 1))
    val fib = Process(0, 1) ++ s.flatMap { case (get, set) =>
      val (prev0, prev1) = get
      val next = prev0 + prev1
      eval(set((prev1, next))).drain ++ emit(next)
    }
    val l = fib.take(10).runLog.run.toList
    l === List(0, 1, 1, 2, 3, 5, 8, 13, 21, 34)
  }

  property("chunkBy2") = secure {
    val s = Process(3, 5, 4, 3, 1, 2, 6)
    s.chunkBy2(_ < _).toList == List(Vector(3, 5), Vector(4), Vector(3), Vector(1, 2, 6)) &&
    s.chunkBy2(_ > _).toList == List(Vector(3), Vector(5, 4, 3, 1), Vector(2), Vector(6))
  }

  property("duration") =  {
    val firstValueDiscrepancy = duration.take(1).runLast.run.get
    val reasonableError = 200 * 1000000 // 200 millis
    (firstValueDiscrepancy.toNanos < reasonableError) :| "duration is near zero at first access"
  }

  implicit def arbVec[A:Arbitrary]: Arbitrary[IndexedSeq[A]] =
    Arbitrary(Gen.listOf(arbitrary[A]).map(_.toIndexedSeq))

  property("zipAll") = forAll((l: IndexedSeq[Int], l2: IndexedSeq[Int]) => {
    val a = Process.range(0,l.length).map(l(_))
    val b = Process.range(0,l2.length).map(l2(_))
    val r = a.tee(b)(tee.zipAll(-1, 1)).runLog.run.toList
    r.toString |: (r == l.zipAll(l2, -1, 1).toList)
  })

  property("passL/R") = secure {
    val a = Process.range(0,10)
    val b: Process[Task,Int] = halt
    a.tee(b)(tee.passL[Int]).runLog.run == List.range(0,10) &&
    b.tee(a)(tee.passR[Int]).runLog.run == List.range(0,10)
  }

  property("cleanup") = secure {
    val a = Process(false).toSource |> await1[Boolean]
    val b = a.orElse(Process.emit(false), Process.emit(true))
    b.cleanup.runLastOr(false).run
  }

  property("onFailure") = secure {
    @volatile var i: Int = 0
    val p = eval(Task.delay(sys.error("FAIL"))) onFailure (Process.emit(1)) map (j => i = j)
    try { p.run.run; false }
    catch { case e: Throwable =>
      e.getMessage == "FAIL" && i == 1
    }
  }

  /*
  This fails
  property("interrupt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v == List(1,2,3,4,6)
  }
  */
}
