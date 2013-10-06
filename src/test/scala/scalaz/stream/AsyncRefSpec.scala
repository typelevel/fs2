package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.concurrent.Task
import scalaz.{-\/, \/-, \/}
import java.lang.Exception

import scalaz.syntax.monad._
import scala.concurrent.SyncVar
import scalaz.stream.async.mutable.Signal
import scalaz.stream.Process.End


 
object AsyncRefSpec extends Properties("async.ref") {

  case object TestedEx extends Exception("expected in test") {
    override def fillInStackTrace = this
  }

  // tests all the operations on the signal (get,set,fail)
  // will also test all the `ref` ops
  property("signal") = forAll {
    l: List[Int] =>
      val signal = async.signal[Int]

      val ops: List[Int => (String, Task[Boolean])] = l.map {
        v =>
          (v % 7).abs match {
            case 0 => (o: Int) => ("get", signal.get.map(_ == o))
            case 1 => (o: Int) => (s"set($v)", signal.set(v) *> signal.get.map(_ == v))
            case 2 => (o: Int) => (s"getAndSet($v)", signal.getAndSet(v).map(r => r == Some(o)))
            case 3 => (o: Int) => (s"compareAndSet(_=>Some($v))", signal.compareAndSet(_ => Some(v)).map(_ == Some(v)))
            case 4 => (o: Int) => ("compareAndSet(_=>None)", signal.compareAndSet(_ => None).map(_ == Some(o)))
            case 5 => (o: Int) => ("ref.async.close", signal.close.map(_ => true))
            case 6 => (o: Int) => ("ref.async.fail(TestedEx)", signal.fail(TestedEx).map(_ => true))
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
                  case \/-(maybeOk) => (signal.get.attemptRun, acc :+(descr, maybeOk))
                  case -\/(failed) => (-\/(failed), acc :+(descr, false))
                }
            }

          case ((-\/(lastErr), acc), n) =>
            //just execute tem all with 0, and record the exception
            n(0) match {
              case (descr, action) =>
                action.attemptRun match {
                  case \/-(unexpected) => (-\/(lastErr), acc :+(descr + " got " + unexpected, true))
                  case -\/(failure) if failure == lastErr => (-\/(lastErr), acc :+(descr, true))
                  case -\/(unexpectedFailure) => (-\/(unexpectedFailure), acc :+(descr, false))
                }


            }
        }


      (runned.filterNot(_._2).size == 0)               :| "no ref action failed" &&
        (runned.size == l.size + 1)                    :| "all actions were run"

  }
  // tests sink
  property("signal.sink") = forAll {
    l: List[Int] =>
      val signal = async.signal[(String, Int)]

      val last = if (l.size % 2 == 0) Signal.Close else Signal.Fail(TestedEx)

      val messages = l.zipWithIndex.map {
        case (i, idx) =>
          import Signal._
          (i % 3).abs match {
            case 0 => Set[(String, Int)]((s"$idx. Set", i))
            case 1 => CompareAndSet[(String, Int)](_ => Some(s"$idx. CompareAndSet", i))
            case 2 => CompareAndSet[(String, Int)](_ => None)

          }
      } :+ last

      val feeded = new SyncVar[Throwable \/ Seq[(String, Int)]]

      Task {
        signal.continuous.runLog.runAsync(feeded.put)
      }.run


      val feeder =
        Process.eval(Task.now(Signal.Set[(String, Int)]("START", 0))) ++
          Process.emitAll(messages).evalMap(e => Task.fork { Thread.sleep(1); Task.now(e) })


      (feeder to signal.sink).attempt().run.attemptRun

      val result = feeded.get(3000)



      (result.isDefined == true)                               :| "got result in time" &&
        (if (last == Signal.Close) {
          (result.get.isRight == true)                         :| "result did not fail" &&
            (result.get.toOption.get.size >= messages.size)    :| "items was emitted" &&
            (signal.get.attemptRun == -\/(End))                :| "Signal is terminated"
        } else {
          (result.get == -\/(TestedEx))                        :| "Exception was raised correctly" &&
            (signal.get.attemptRun == -\/(TestedEx))           :| "Signal is failed"
        })


  }


  // tests the discrete stream so it would not advance when not changed. 
  // additionally tests that it would not advance when compareAndSet results to None - not set
  property("discrete") = forAll {
    l: List[Int] =>
      val initial = None
      val feed = l.distinct.sorted.map(Some(_))

      val ref = async.ref[Option[Int]]
      ref.set(initial)


      val d1 = ref.signal.discrete

      val d2 = ref.signal.discrete

      val sync1 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d1.runLog.runAsync(sync1.put)

      val sync2 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d2.runLog.runAsync(sync2.put)
    
    
      Task {
        feed.foreach { v => 
          ref.set(v); Thread.sleep(5)
        }
        ref.close
      }.run
     
      
      sync1.get(3000).nonEmpty                          :| "First process finished in time" &&
      sync2.get(3000).nonEmpty                          :| "Second process finished in time" &&
      (sync1.get.isRight && sync2.get.isRight)          :| "both processes finished ok" &&
      (sync1.get.toOption.get.head == initial )         :| "First process got first Get immediately" &&
      (sync2.get.toOption.get.head == initial )         :| "Second process got first Get immediately" &&
      (sync1.get.toOption.get.size <= feed.size + 1)    :| "First process got only elements when changed" &&
      (sync2.get.toOption.get.size <= feed.size + 1 )   :| "First process got only elements when changed" 
  }


}
