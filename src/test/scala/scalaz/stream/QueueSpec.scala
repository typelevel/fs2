package scalaz.stream

import Cause._
import org.scalacheck.{Gen, Properties, Arbitrary}
import org.scalacheck.Prop._
import scala.concurrent.SyncVar
import scalaz.concurrent.Task
import scalaz.{-\/, \/-, \/}
import scala.concurrent.duration._

class QueueSpec extends Properties("queue") {
  implicit val scheduler = scalaz.stream.DefaultScheduler

  property("circular-buffer") = forAll(Gen.posNum[Int], implicitly[Arbitrary[List[Int]]].arbitrary) {
    (bound: Int, xs: List[Int]) =>
      val b = async.circularBuffer[Int](bound)
      val collected = new SyncVar[Throwable\/IndexedSeq[Int]]
      val p = ((Process.emitAll(xs):Process[Task,Int]) to b.enqueue).run.timed(3000).attempt.run
      b.dequeue.runLog.runAsync(collected.put)
      b.close.run
      val ys = collected.get(3000).map(_.getOrElse(Nil)).getOrElse(Nil)
      b.close.runAsync(_ => ())

      ("Enqueue process is not blocked" |: p.isRight)
      ("Dequeued a suffix of the input" |: (xs endsWith ys)) &&
      ("No larger than the bound" |: (ys.length <= bound)) &&
      (s"Exactly as large as the bound (got ${ys.length})" |:
        (xs.length < bound && ys.length == xs.length || ys.length == bound))
  }

  property("basic") = forAll {
    l: List[Int] =>
      val q = async.unboundedQueue[Int]
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
      val q = async.unboundedQueue[Int]
      val t1 = Task { l.foreach(i => q.enqueueOne(i).run) }
      val t2 = q.dequeue.runLog
      val t3 = q.size.discrete.runLog

      val sizes = new SyncVar[Throwable \/ IndexedSeq[Int]]
      t3.runAsync(sizes.put)

      Thread.sleep(100) // delay to give chance for the `size` signal to register

      t1.run

      val values = new SyncVar[Throwable \/ IndexedSeq[Int]]
      t2.runAsync(values.put)

      Thread.sleep(100) // delay to give chance for the `size` signal to collect all values

      q.close.run

      val expectedSizes =
        if (l.isEmpty) Vector(0)
        else {
          val up =
            (0 to l.size).toSeq
          up ++ up.reverse.drop(1)
        }

      (values.get(3000) == Some(\/-(l.toVector))) :| s"all values collected ${values.get(0)}" &&
        (sizes.get(3000) == Some(\/-(expectedSizes.toVector))) :| s"all sizes collected ${sizes.get(0)} expected ${expectedSizes}"
  }


  property("enqueue-distribution") = forAll {
    l: List[Int] =>
      val q = async.unboundedQueue[Int]
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
    val q = async.unboundedQueue[Int]
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

    //try publish, shall be empty, terminated, return End
    val publishClosed = q.enqueueOne(1).attemptRun
    //subscribe, shall be terminated
    val subscribeClosed = t3.attemptRun

    (dequeued.get(3000) == Some(\/-(Vector(1,2,3)))) :| s"Queue was terminated ${dequeued.get(0)}" &&
      (publishClosed == -\/(Terminated(End))) :| s"Publisher is closed before elements are drained $publishClosed" &&
      ((subscribeClosed == \/-(Vector()))) :| s"Subscriber is closed after elements are drained $subscribeClosed"


  }


  // tests situation where killed process may `swallow` item in queue
  // from the process that is running
  property("queue-swallow-killed") = secure {
    val q = async.unboundedQueue[Int]
    val sleeper = time.sleep(1 second)
    val signalKill = Process(false).toSource ++ sleeper ++ Process(true)

    signalKill.wye(q.dequeue)(wye.interrupt).runLog.run
    q.enqueueOne(1).run
    val r = q.dequeue.take(1).runLog.run

    r == Vector(1)
  }

  property("dequeue-batch.basic") = forAll { l: List[Int] =>
    val q = async.unboundedQueue[Int]
    val t1 = Task {
      l.foreach(i => q.enqueueOne(i).run)
      q.close.run
    }

    val collected = new SyncVar[Throwable\/IndexedSeq[Seq[Int]]]

    q.dequeueAvailable.runLog.runAsync(collected.put)
    t1.runAsync(_=>())

    "Items were collected" |:  collected.get(3000).nonEmpty &&
      (s"All values were collected, all: ${collected.get(0)}, l: $l " |: collected.get.getOrElse(Nil).flatten == l)
  }

  property("dequeue-batch.chunked") = secure {
    import Function.const

    val q = async.unboundedQueue[Int]

    val pump = for {
      chunk <- q.dequeueAvailable
      _ <- Process eval ((Process emitAll (0 until (chunk.length * 2) map const(chunk.length)) toSource) to q.enqueue).run     // do it in a sub-process to avoid emitting a lot of things
    } yield chunk

    val collected = new SyncVar[Throwable \/ IndexedSeq[Seq[Int]]]

    (pump take 4 runLog) timed 3000 runAsync (collected.put)

    q.enqueueOne(1) runAsync { _ => () }

    collected.get(5000).nonEmpty :| "items were collected" &&
      ((collected.get getOrElse Nil) == Seq(Seq(1), Seq(1, 1), Seq(2, 2, 2, 2), Seq(4, 4, 4, 4, 4, 4, 4, 4))) :| s"saw ${collected.get getOrElse Nil}"
  }

  property("dequeue-batch.chunked-limited") = secure {
    import Function.const

    val q = async.unboundedQueue[Int]

    val pump = for {
      chunk <- q.dequeueBatch(2)
      _ <- Process eval ((Process emitAll (0 until (chunk.length * 2) map const(chunk.length)) toSource) to q.enqueue).run     // do it in a sub-process to avoid emitting a lot of things
    } yield chunk

    val collected = new SyncVar[Throwable \/ IndexedSeq[Seq[Int]]]

    (pump take 5 runLog) timed 3000 runAsync (collected.put)

    q.enqueueOne(1) runAsync { _ => () }

    collected.get(5000).nonEmpty :| "items were collected" &&
      ((collected.get getOrElse Nil) == Seq(Seq(1), Seq(1, 1), Seq(2, 2), Seq(2, 2), Seq(2, 2))) :| s"saw ${collected.get getOrElse Nil}"
  }

  property("dequeue.preserve-data-in-error-cases") = forAll { xs: List[Int] =>
    xs.nonEmpty ==> {
      val q = async.unboundedQueue[Int](true)
      val hold = new SyncVar[Unit]

      val setup = (Process emitAll xs toSource) to q.enqueue run

      val driver = q.dequeue to (Process fail (new RuntimeException("whoops"))) onComplete (Process eval_ (Task delay { hold.put(()) }))

      val safeDriver = driver onHalt {
        case Error(_) => Process.Halt(End)
        case rsn => Process.Halt(rsn)
      }

      val recovery = for {
        _ <- Process eval (Task delay { hold.get })
        i <- q.dequeue take xs.length
      } yield i

      setup.run

      val results = (safeDriver merge recovery).runLog.timed(3000).run
      (results == xs) :| s"got $results"
    }
  }

  property("dequeue.take-1-repeatedly") = secure {
    val q = async.unboundedQueue[Int]
    q.enqueueAll(List(1, 2, 3)).run

    val p = for {
      i1 <- q.dequeue take 1
      i2 <- q.dequeue take 1
      i3 <- q.dequeue take 1
    } yield List(i1, i2, i3)

    p.runLast.run == Some(List(1, 2, 3))
  }
}
