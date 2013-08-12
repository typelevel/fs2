package scalaz.stream

import scalaz.{Equal, Nondeterminism}
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._

import org.scalacheck._
import Prop._

object ProcessSpec extends Properties("Process1") {
  
  import Process._
  import process1._

  implicit def EqualProcess[A:Equal]: Equal[Process0[A]] = new Equal[Process0[A]] {
    def equal(a: Process0[A], b: Process0[A]): Boolean = 
      a.toList == b.toList
  }
  implicit def ArbProcess0[A:Arbitrary]: Arbitrary[Process0[A]] = 
    Arbitrary(Arbitrary.arbitrary[List[A]].map(a => Process(a: _*)))

  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) => 
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
    }) &&
    ("fold" |: {
      p.toList.scanLeft(0)(_ - _) ===
      p.toSource.fold(0)(_ - _).collect.run.toList
    }) &&
    ("sum" |: {
      p.toList.sum[Int] ===
      p.toSource.pipe(process1.sum).runLastOr(0).run
    }) &&
    ("intersperse" |: {
      p.intersperse(0).toList == p.toList.intersperse(0) 
    })
  }

  property("fill") = forAll(Gen.choose(0,30).map2(Gen.choose(0,50))((_,_))) { 
    case (n,chunkSize) => Process.fill(n)(42, chunkSize).collect.run.toList == List.fill(n)(42) 
  }

  import scalaz.concurrent.Task

  property("exception") = secure { 
    case object Err extends RuntimeException 
    val tasks = Process(Task(1), Task(2), Task(throw Err), Task(3))
    (try { tasks.eval.pipe(processes.sum).collect.run; false }
     catch { case Err => true }) &&
    (try { io.collectTask(tasks.eval.pipe(processes.sum)); false }
     catch { case Err => true }) &&
    (tasks.eval.pipe(processes.sum).
      handle { case e: Throwable => -6 }.
      collect.run.last == -6)
  }

  property("enqueue") = secure {
    val tasks = Process.range(0,1000).map(i => Task { Thread.sleep(1); 1 })
    tasks.sequence(50).pipe(processes.sum[Int].last).collect.run.head == 1000 &&
    tasks.gather(50).pipe(processes.sum[Int].last).collect.run.head == 1000
  }

  // ensure that zipping terminates when the smaller stream runs out
  property("zip one side infinite") = secure {
    val ones = Process.wrap(Task.now(1)).repeat 
    val p = Process(1,2,3) 
    ones.zip(p).collect.run == IndexedSeq(1 -> 1, 1 -> 2, 1 -> 3) &&
    p.zip(ones).collect.run == IndexedSeq(1 -> 1, 2 -> 1, 3 -> 1) 
  }

  property("merge") = secure {
    val t0 = System.currentTimeMillis
    val sleepsL = List(0L, 998L, 1000L) map (i => Task { Thread.sleep(i); i }) 
    val sleepsR = List(500L, 999L, 1001L) map (i => Task { Thread.sleep(i); i })
    val ts = emitSeq(sleepsL).eval.merge(emitSeq(sleepsR).eval).
             map(i => (i, System.currentTimeMillis - t0)).
             collect.run.toList
    // println { 
    //   "Actual vs expected elapsed times for merged stream; doing fuzzy compare:\n" + 
    //   ts.map(_._2).zip(List(0L, 500L, 1000L, 1500L, 2000L, 2500L))
    // }
    ts.map(_._1) == List(0L, 500L, 998L, 999L, 1000L, 1001L) &&
    ts.map(_._2).zip(List(0L, 500L, 1000L, 1500L, 2000L, 2500L)).
      forall { case (actual,expected) => (actual - expected).abs < 500L }
  }

  property("actor.queue") = forAll { l: List[Int] => 
    val (q, s) = actor.queue[Int]
    import message.queue._
    val t1 = Task { 
      l.foreach(i => q ! enqueue(i))
      q ! close
    }
    val t2 = s.collect

    Nondeterminism[Task].both(t1, t2).run._2.toList == l
  }

  property("actor.variable") = forAll { l: List[Int] => 
    val (v, s) = actor.variable[Int]
    import message.variable._
    val t1 = Task { 
      l.foreach { i => v ! set(i); Thread.sleep(1) }
      v ! close
    }
    val t2 = s.takeWhile(_ % 23 != 0).collect

    Nondeterminism[Task].both(t1, t2).run._2.toList.forall(_ % 23 != 0)
  }
}

