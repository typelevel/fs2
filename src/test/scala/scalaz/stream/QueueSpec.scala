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

      val collected = new SyncVar[Throwable\/IndexedSeq[Int]]

      q.dequeue.runLog.runAsync(collected.put)
      t1.runAsync(_=>())
 
      "Items were collected" |:  collected.get(3000).nonEmpty &&
        (s"All values were collected, all: ${collected.get(0)}, l: $l " |: collected.get.getOrElse(Nil) == l)

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

      val c2 = new SyncVar[Throwable\/IndexedSeq[Int]]
      val c3 = new SyncVar[Throwable\/IndexedSeq[Int]]
      q.dequeue.runLog.runAsync(c2.put)
      q.dequeue.runLog.runAsync(c3.put)
      t1.runAsync(_=>())

      "Both partial collections were collected" |: (c2.get(3000).nonEmpty && c3.get(3000).nonEmpty) &&
        (s"all items has been received, c2: $c2, c3: $c3, l: $l" |:
          (c2.get.getOrElse(Nil) ++ c3.get.getOrElse(Nil)).sorted == l.sorted)
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

    //t2 dequeues and must have all l in it
    val dequeued = new SyncVar[Throwable \/ IndexedSeq[Int]]
    t2.runAsync(dequeued.put)

    //start pushing ++ stop, and that should make t2 to stop too
    t1.timed(3000).run

    //try publish, shall be empty, terminated
    val publishClosed = q.enqueueOne(1).attemptRun
    //subscribe, shall be terminated
    val subscribeClosed = t3.attemptRun

    (dequeued.get(3000) == Some(\/-(Vector(1,2,3)))) :| "Queue was terminated" &&
      (publishClosed == \/-(())) :| "Publisher is closed before elements are drained" &&
      ((subscribeClosed == \/-(Vector()))) :| "Subscriber is closed after elements are drained"


  }


}
