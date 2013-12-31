package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties
import scala.concurrent.SyncVar
import scalaz.concurrent.Task
import scalaz.{Nondeterminism, \/-, \/}

object QueueSpec extends Properties("queue") {

  property("basic") = forAll {
    l: List[Int] =>
      val q = async.boundedQueue[Int]()
      val t1 = Task {
        l.foreach(i => q.enqueueOne(i).run)
        q.close.run
      }
      val t2 = q.dequeue.runLog

      Nondeterminism[Task].both(t1, t2).run._2.toList == l
  }


  property("size-signal") = forAll {
    l: List[Int] =>
      val q = async.boundedQueue[Int]()
      val t1 = Task { l.foreach(i => q.enqueueOne(i).run) }
      val t2 = q.dequeue.runLog
      val t3 = q.size.discrete.runLog

      val sizes = new SyncVar[Throwable \/ IndexedSeq[Int]]
      t3.runAsync(sizes.put)

      t1.run

      val values = new SyncVar[Throwable \/ IndexedSeq[Int]]
      t2.runAsync(values.put)

      q.close.run

      val expectedSizes =
        if (l.isEmpty) Vector(0)
        else {
          val up =
            (0 to l.size).toSeq
          up ++ up.reverse.drop(1)
        }

      (values.get(3000) == Some(\/-(l.toVector))) :| "all values collected" &&
        (sizes.get(3000) == Some(\/-(expectedSizes.toVector))) :| "all sizes collected"
  }


  property("enqueue-distribution") = forAll {
    l: List[Int] =>
      val q = async.boundedQueue[Int]()
      val t1 = Task {
        l.foreach(i => q.enqueueOne(i).run)
        q.close.run
      }
      val t2 = q.dequeue.runLog
      val t3 = q.dequeue.runLog

      val t23 = Nondeterminism[Task].both(t2, t3)

      val (c2, c3) = Nondeterminism[Task].both(t1, t23).run._2

      ((c2 ++ c3).toSet == l.toSet) :| "all items has been received"
  }



  property("closes-pub-sub") = secure {
    val l: List[Int] = List(1, 2, 3)
    val q = async.boundedQueue[Int]()
    val t1 = Task {
      l.foreach(i => q.enqueueOne(i).run)
      q.close.run
    }
    val t2 = q.dequeue.runLog
    val t3 = q.dequeue.runLog

    val closed = new SyncVar[Throwable \/ Unit]

    t1.runAsync(closed.put)

    Thread.sleep(500)

    val publishClosed = q.enqueueOne(1).attemptRun.isLeft
    t2.run

    val subscribeClosed = t3.attemptRun

    publishClosed :| "Publisher is closed before elements are drained" &&
      (subscribeClosed == \/-(List())) :| "Subscriber is closed after elements are drained" &&
      (closed.get(3000) == Some(\/-(()))) :| "Queue was terminated"

  }


}
