package scalaz.stream

import Cause._
import org.scalacheck.Prop._
import org.scalacheck.Properties

import scala.concurrent.SyncVar
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.stream.async.mutable.Signal
import scalaz.syntax.monad._
import scalaz.{-\/, Nondeterminism, \/, \/-}

object AsyncSignalSpec extends Properties("async.signal") {

  implicit val scheduler = DefaultScheduler

  property("basic") = forAll { l: List[Int] =>
    val v = async.signal[Int]
    val s = v.continuous
    val t1 = Task {
      l.foreach { i => v.set(i).run; Thread.sleep(1) }
      v.close.run
    }
    val t2 = s.takeWhile(_ % 23 != 0).runLog

    Nondeterminism[Task].both(t1, t2).run._2.toList.forall(_ % 23 != 0)
  }


  // tests all the operations on the signal (get,set,fail)
  property("signal-ops") = forAll {
    l: List[Int] =>
      val signal = async.signal[Int]

      val ops: List[Int => (String, Task[Boolean])] = l.map {
        v =>
          (v % 5).abs match {
            case 0 => (o: Int) => ("get", signal.get.map(_ == o))
            case 1 => (o: Int) => (s"set($v)", signal.set(v) *> signal.get.map(_ == v))
            case 2 => (o: Int) => (s"getAndSet($v)", signal.getAndSet(v).map(r => r == Some(o)))
            case 3 => (o: Int) => (s"compareAndSet(_=>Some($v))", signal.compareAndSet(_ => Some(v)).map(_ == Some(v)))
            case 4 => (o: Int) => ("compareAndSet(_=>None)", signal.compareAndSet(_ => None).map(_ == Some(o)))
          }
      }

      //initial set
      signal.set(0).run

      val (_, runned) =
        ops.foldLeft[(Throwable \/ Int, Seq[(String, Boolean)])]((\/-(signal.get.run), Seq(("START", true)))) {
          case ((\/-(last), acc), n) =>
            n(last) match {
              case (descr, action) =>
                action.attemptRun match {
                  case \/-(maybeOk) => (signal.get.attemptRun, acc :+((descr, maybeOk)))
                  case -\/(failed) => (-\/(failed), acc :+((descr, false)))
                }
            }

          case ((-\/(lastErr), acc), n) =>
            //just execute item all with 0, and record the exception
            n(0) match {
              case (descr, action) =>
                action.attemptRun match {
                  case \/-(unexpected) => (-\/(lastErr), acc :+((descr + " got " + unexpected, true)))
                  case -\/(failure) if failure == lastErr || failure == End => (-\/(lastErr), acc :+((descr, true)))
                  case -\/(unexpectedFailure) => (-\/(unexpectedFailure), acc :+((descr, false)))
                }


            }
        }

      signal.close.run

      (runned.filterNot(_._2).size == 0)               :| "no ref action failed" &&
        (runned.size == l.size + 1)                    :| "all actions were run"

  }


  // tests sink
  property("signal.sink") = forAll {
    l: List[Int] =>
      val signal = async.signal[(String, Int)]

      val closeSignal =
        time.sleep(100 millis) ++
          (if (l.size % 2 == 0) Process.eval_(signal.close)
          else Process.eval_(signal.fail(Bwahahaa)))

      val messages = l.zipWithIndex.map {
        case (i, idx) =>
          import scalaz.stream.async.mutable.Signal._
          (i % 3).abs match {
            case 0 => Set[(String, Int)]((s"$idx. Set", i))
            case 1 => CompareAndSet[(String, Int)](_ => Some((s"$idx. CompareAndSet", i)))
            case 2 => CompareAndSet[(String, Int)](_ => None)

          }
      }

      val feeded = new SyncVar[Throwable \/ Seq[(String, Int)]]

      Task { signal.continuous.runLog.runAsync(feeded.put) }.run


      val feeder =
        Process.eval(Task.now(Signal.Set[(String, Int)](("START", 0)))) ++
          Process.emitAll(messages).map(e => Task.fork { Thread.sleep(1); Task.now(e) }).eval



      (feeder to signal.sink).attempt().onComplete(closeSignal).run.attemptRun

      val result = feeded.get(3000)


      (result.isDefined == true) :| "got result in time" &&
        (if (l.size % 2 == 0) {
          (result.get.isRight == true) :| "result did not fail" &&
            (result.get.toOption.get.size >= messages.size) :| "items was emitted" &&
            (signal.get.attemptRun == -\/(Terminated(End))) :| "Signal is terminated"
        } else {
          (result.get == -\/(Bwahahaa)) :| s"Exception was raised correctly : $result, term ${l.size % 2 == 0 }" &&
            (signal.get.attemptRun == -\/(Bwahahaa)) :| "Signal is failed"
        })

  }


  // tests the discrete stream so it would contain all discrete values set
  property("discrete") = forAll {
    l: List[Int] =>
      val initial = None
      val feed = l.map(Some(_))

      val ref = async.signal[Option[Int]]
      ref.set(initial).run


      val d1 = ref.discrete.take(l.size+1)

      val d2 = ref.discrete.take(l.size+1)

      val sync1 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d1.runLog.runAsync(sync1.put)

      val sync2 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d2.runLog.runAsync(sync2.put)


      Task {
        feed.foreach { v =>  ref.set(v).run }
      }.run


      sync1.get(3000).nonEmpty                          :| "First process finished in time" &&
        sync2.get(3000).nonEmpty                          :| "Second process finished in time" &&
        (sync1.get.isRight && sync2.get.isRight)          :| "both processes finished ok" &&
        (sync1.get.toOption.get == None +: feed)          :| "first process get all values signalled" &&
        (sync2.get.toOption.get == None +: feed)          :| "second process get all values signalled"
  }


  //tests a signal from discrete process
  property("from.discrete") = secure {
    val sig = async.toSignal[Int](Process(1,2,3,4).toSource)
    sig.discrete.take(4).runLog.run == Vector(1,2,3,4)
  }

  //tests that signal terminates when discrete process terminates
  property("from.discrete.terminates") = secure {
    val sleeper = Process.eval_{Task.delay(Thread.sleep(1000))}
    val sig = async.toSignal[Int](sleeper ++ Process(1,2,3,4).toSource)
    val initial = sig.discrete.runLog.run
    val afterClosed = sig.discrete.runLog.run
    (initial == Vector(1,2,3,4)) && (afterClosed== Vector())
  }

  //tests that signal terminates with failure when discrete process terminates with failure
  property("from.discrete.fail") = secure {
    val sleeper = Process.eval_{Task.delay(Thread.sleep(1000))}
    val sig = async.toSignal[Int](sleeper ++ Process(1,2,3,4).toSource ++ Process.fail(Bwahahaa))
    sig.discrete.runLog.attemptRun == -\/(Bwahahaa)
  }

  // tests that signal terminates with failure when discrete
  // process terminates with failure even when haltOnSource is set to false
  property("from.discrete.fail.always") = secure {
    val sleeper = Process.eval_{Task.delay(Thread.sleep(1000))}
    val sig = async.toSignal[Int](sleeper ++ Process(1,2,3,4).toSource ++ Process.fail(Bwahahaa))
    sig.discrete.runLog.attemptRun == -\/(Bwahahaa)
  }

  property("continuous") = secure {
    val sig = async.signalUnset[Int]
    time.awakeEvery(100.millis)
      .zip(Process.range(1, 13))
      .map(x => Signal.Set(x._2))
      .to(sig.sink)
      .run
      .runAsync(_ => ())
    val res = time.awakeEvery(500.millis)
      .zip(sig.continuous)
      .map(_._2)
      .take(6)
      .runLog.run.toList
    sig.close.run
    // res(0) was read at 500 ms so it must contain value 4 or 5 which were published at 400 ms or 500 ms.
    (res(0) >= 4 && res(0) <= 5) :| s"res(0) == ${res(0)}" &&
      // res(1) was read at 1000 ms so it must contain value 9 or 10 which were published at 900 ms or 1000 ms.
      (res(1) >= 9 && res(1) <= 10) :| s"res(1) == ${res(1)}" &&
      // res(2) was read at 1500 ms so it must contain value 12 which was published at 1200 ms.
      (res(2) == 12) :| s"res(2) == ${res(2)}" &&
      // res(5) was read at 3000 ms and so it must still contain value 12.
      (res(5) == 12) :| s"res(5) == ${res(5)}"
  }


  property("signalOf.init.value") = secure {
    val timer = time.sleep(1.second)
    val signal = async.signalOf(0)

    (eval_(signal.set(1)) ++ signal.discrete.once ++ timer ++ signal.discrete.once).runLog.run ?= Vector(1,1)
  }
}
