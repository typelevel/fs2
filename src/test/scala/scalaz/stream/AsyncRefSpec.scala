package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.concurrent.Task
import scalaz.{-\/, \/-, \/, Nondeterminism}
import scalaz.stream.message.ref
import java.lang.Exception

import scalaz.syntax.monad._
import scala.concurrent.SyncVar



/**
 *
 * User: pach
 * Date: 9/2/13
 * Time: 9:17 AM
 * (c) 2011-2013 Spinoco Czech Republic, a.s.
 */
object AsyncRefSpec extends Properties("async.ref") {

  case object TestedEx extends Exception("expected in test") {
    override def fillInStackTrace = this
  }
  
  // tests all the operations on the ref (get,set,fail)
  property("ops") = forAll {
    l: List[Int] =>
      val ref = async.ref[Int]

      val ops: List[Int => (String, Task[Boolean])] = l.map {
        v =>
          (v % 7).abs match {
            case 0 => (o: Int) => ("get", ref.async.get.map(_ == o))
            case 1 => (o: Int) => (s"set($v)", ref.async.set(v) *> ref.async.get.map(_ == v))
            case 2 => (o: Int) => (s"getAndSet($v)", ref.async.getAndSet(v).map(r => r == Some(o)))
            case 3 => (o: Int) => (s"compareAndSet(_=>Some($v))", ref.async.compareAndSet(_ => Some(v)).map(_ == Some(v)))
            case 4 => (o: Int) => ("compareAndSet(_=>None)", ref.async.compareAndSet(_ => None).map(_ == Some(o)))
            case 5 => (o: Int) => ("ref.async.close", ref.async.close.map(_ => true))
            case 6 => (o: Int) => ("ref.async.fail(TestedEx)", ref.async.fail(TestedEx).map(_ => true))
          }
      }

      //initial set
      ref.async.set(0).run

      val (_, runned) =
        ops.foldLeft[(Throwable \/ Int, Seq[(String, Boolean)])]((\/-(ref.async.get.run), Seq(("START", true)))) {
          case ((\/-(last), acc), n) =>
            n(last) match {
              case (descr, action) =>
                action.attemptRun match {
                  case \/-(maybeOk) => (ref.async.get.attemptRun, acc :+(descr, maybeOk))
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


      (runned.filterNot(_._2).size == 0) :| "no ref action failed" &&
        (runned.size == l.size + 1) :| "all actions were run"

  }
  
  // tests the discrete stream so it would not advance when not changed. 
  // additionally tests that it would not advance when compareAndSet results to None - not set
  property("discrete") = forAll {
    l: List[Int] => 
      val initial = None
      val feed = l.distinct.sorted.map(Some(_))
    
      val ref = async.ref[Option[Int]]
      ref.set(initial)
     
    
      val d1 = ref.toDiscreteSource
    
      val d2 = ref.toDiscreteSource
    
      val sync1 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d1.collect.runAsync(sync1.put)

      val sync2 = new SyncVar[Throwable \/ Seq[Option[Int]]]
      d2.collect.runAsync(sync2.put)
    
    
      Task {
        feed.foreach {v => 
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
