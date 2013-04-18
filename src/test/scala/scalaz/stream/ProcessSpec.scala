package scalaz.stream

import scalaz.Equal
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._

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
    })
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
    tasks.sequence(50).pipe(processes.sum[Int].last).collect.run.head == 1000
  }
}

