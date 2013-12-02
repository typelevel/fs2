package scalaz.stream

import scalaz.{\/, Equal, Nondeterminism}
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task
import scala.concurrent.SyncVar

object AsyncSpec extends Properties("async") {
  
  property("queue") = forAll { l: List[Int] => 
    val (q, s) = async.queue[Option[Int]]

    val r = new SyncVar[Throwable\/IndexedSeq[Int]]
    s.takeWhile(_.isDefined)
      .collect{ case Some(x) => x}.runLog.timed(3000)
      .runAsync(r.put)

    Task {
      l.foreach(i => q.enqueue(Some(i)))
      q.enqueue(None)
    }.run

    r.get(3000).toSeq.flatMap(_.toList.flatten) == l
  }

  property("ref") = forAll { l: List[Int] => 
    val v = async.ref[Int]
    val s = v.signal.continuous
    val t1 = Task {
      l.foreach { i => v.set(i); Thread.sleep(1) }
      v.close
    }
    val t2 = s.takeWhile(_ % 23 != 0).runLog

    Nondeterminism[Task].both(t1, t2).run._2.toList.forall(_ % 23 != 0)
  }

}
