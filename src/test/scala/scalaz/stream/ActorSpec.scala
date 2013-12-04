package scalaz.stream

import scalaz._
import scalaz.\/._

import org.scalacheck._
import Prop._

import scalaz.concurrent.{Actor, Task}
import scalaz.stream.Process.End

import java.lang.Exception
import scala.concurrent.SyncVar

import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit, CountDownLatch}

import collection.JavaConverters._
import collection.JavaConversions._
import scala.Predef._
import scalaz.\/-
import scalaz.stream.actor.message
import scalaz.stream.actor.message.ref.Fail
import scalaz.stream.actor.actors

object ActorSpec extends Properties("actor") {

  property("queue") = forAll {
    l: List[Int] =>
      val (q, s) = actors.queue[Int]
      import message.queue._
      val t1 = Task {
        l.foreach(i => q ! enqueue(i))
        q ! close
      }
      val t2 = s.runLog

      Nondeterminism[Task].both(t1, t2).run._2.toList == l
  }
   
  case object TestedEx extends Exception("expected in test") {
    override def fillInStackTrace = this
  }

  def awaitFailure[A](a: Actor[message.ref.Msg[A]], t: Throwable): Seq[Throwable] = {
    Task.async[Seq[Throwable]] {
      cb =>
        a ! Fail(t, t => cb(\/-(Seq(t))))
    }.run
  }

  def awaitClose[A](a: Actor[message.ref.Msg[A]]): Seq[Throwable] = awaitFailure(a, End)


  type CLQ[A] = ConcurrentLinkedQueue[A]
  type Read = (Int, Option[Int])
  type CallBack = (Int, Throwable \/ Option[Int])
  type RefActor[A] = Actor[message.ref.Msg[A]]


  case class TestResult(result: List[Int]
                        , read: List[Read]
                        , calledBack: List[CallBack]
                        , failCallBack: List[Throwable])

  def example[A, B](l: List[Int], lsize: Int = -1, actorStream: (Actor[message.ref.Msg[A]], Process[Task, A]) = actors.ref[A])
                   (pf: (Process[Task, A]) => Process[Task, B])
                   (feeder: (RefActor[A], CLQ[Read], CLQ[CallBack], CLQ[Throwable], CountDownLatch) => Task[Unit])
                   (vf: (List[B], List[Read], List[CallBack], List[Throwable], Long) => Prop): Prop = {


    val (a, s) = actorStream

    val read = new ConcurrentLinkedQueue[(Int, Option[Int])]()
    val calledBack = new ConcurrentLinkedQueue[(Int, Throwable \/ Option[Int])]()
    val failCallBack = new ConcurrentLinkedQueue[Throwable]()
    val latch = new CountDownLatch(if (lsize < 0) l.size else lsize)

    val t1: Task[Unit] = feeder(a, read, calledBack, failCallBack, latch).map(_ => {
      latch.await(9, TimeUnit.SECONDS)
    })
    val t2 = pf(s).runLog

    val res = new SyncVar[Throwable \/ Seq[B]]
    Nondeterminism[Task].both(t1, t2).runAsync(cb => res.put(cb.map(_._2)))

    vf(res.get(10000).getOrElse(right(List[B]())).map(_.toList).getOrElse(List[B]())
      , read.asScala.toList
      , calledBack.asScala.toList
      , failCallBack.asScala.toList
      , latch.getCount)


  }


  //initial basic continuous stream test
  //Just sets all the values from list and verify we have got expected order, callbacks, reads 
  property("ref.basic") = forAll {
    (l: List[Int]) =>
      example[Int, Int](l)(s => s.takeWhile(_ % 23 != 0))({
        import message.ref._
        (a, read, callback, failedCallback, latch) =>
          Task {
            l.foreach {
              i =>
                a ! Set(r => {read.add(i, r); Some(i) }
                  , cbv => {callback.add(i, cbv); latch.countDown() }
                  , false)
                Thread.sleep(1)
            }
            failedCallback.addAll(awaitClose(a))
          }
      })({
        (res, read, callback, failedCallback, latch) =>
          (latch == 0L) :| "All callbacks were called" &&
            (res.forall(_ % 23 != 0)) :| "Stream  got some elements" &&
            ((l zip (None +: l.map(Some(_)))) == read.toList) :| "Initial values are ok" &&
            ((l zip l.map(v => right(Some(v)))).sortBy(_._1) == callback.sortBy(_._1)) :| "CallBacks are ok" &&
            (failedCallback == List(End)) :| "FailCallBack is ok"
      })
  }


  //checks that once terminated it will not feed anything else to process
  //first feed include, then fail the ref and send exclude. excludes are then verified to fail in result
  property("ref.stop-on-fail") = forAll {

    l: (List[Int]) =>

      val include: List[Int] = l.takeWhile(_ % 23 != 0)
      val exclude: List[Int] = l.filterNot(include.contains(_))

      example[Int, Throwable \/ Int](l, include.size + exclude.size)(s => s.attempt())({
        import message.ref._
        (a, read, callback, failedCallback, latch) =>
          Task {
            def feed(i: Int) = {
              a ! Set(r => {read.add(i, r); Some(i) }
                , cbv => {callback.add(i, cbv); latch.countDown() }
                , false)
              Thread.sleep(1)
            }

            include.foreach(feed)
            failedCallback.addAll(awaitFailure(a, TestedEx))
            exclude.foreach(feed)
            failedCallback.addAll(awaitClose(a))
          }
      })({
        (res, read, callback, failedCallback, latch) =>



          res.collect { case \/-(r) => r }.forall(e => !exclude.contains(e)) :| "Stream was fed before End" &&
            (res.lastOption == Some(-\/(TestedEx))) :| "Last collected is Exception" &&
            ((include zip (None +: include.map(Some(_)))) == read.toList) :| "Initial values are ok" &&
            (latch == 0L) :| "All callbacks were called" &&
            ((((include zip include.map(v => right(Some(v)))) ++
              (exclude zip exclude.map(v => left(TestedEx)))).sortBy(_._1) == callback.sortBy(_._1))) :| "CallBacks are ok" &&
            (failedCallback == List(TestedEx, TestedEx)) :| "FailCallBacks are ok"

      })


  }


  //checks it would never emit if the Set `f` would result in None
  property("ref.no-set-behaviour") = forAll {
    l: List[Int] =>

      example[Int, Int](l)(s => s.takeWhile(_ % 23 != 0))({
        import message.ref._
        (a, read, callback, failedCallback, latch) =>
          Task {
            l.foreach {
              i =>
                a ! Set(r => {read.add(i, r); None }
                  , cbv => {callback.add(i, cbv); latch.countDown() }
                  , false)
                Thread.sleep(1)
            }
            failedCallback.addAll(awaitClose(a))
          }
      })({
        (res, read, callback, failedCallback, latch) =>
 
          (res.size == 0) :| "Stream is empty" &&
            ((l zip (l.map(_ => None))) == read.toList) :| "Initial values are ok" &&
            (latch == 0L) :| "All callbacks were called" &&
            ((l zip l.map(_ => right(None))).sortBy(_._1) == callback.sortBy(_._1)) :| "CallBacks are ok" &&
            (failedCallback == List(End)) :| "FailCallBack is ok"
      })

  }


  // checks Get-only-when-changed behaviour. Like Get, only it will wait always for value to change.
  // will also test if the None is result of Set(f,_,_) it would not set the value of ref
  // odd values are == None, even values are set (== Some(i)). 
  property("ref.get-when-changed") = forAll {
    l: List[Int] =>
      import message.ref._

      @volatile var currentSerial = 0
      @volatile var serialChanges = Vector[(Int, Int)]()

      val vs@(v, _) = actors.ref[Int]

      val serials = Process.repeatEval {
        Task.async[Int] {
          cb =>
            v ! Get(cbv =>
              cb(cbv.map(e => {
                serialChanges = serialChanges :+(currentSerial, e._1)
                currentSerial = e._1
                e._2
              }))
              , true
              , currentSerial)
        }
      }

      val feed: List[Int] = l.distinct.sorted

      example[Int, Int](l, feed.size, vs)(_ => serials)({
        import message.ref._
        (a, read, callback, failedCallback, latch) =>

          Task[Unit] {
            feed.foreach {
              case i if i % 2 == 0 =>
                a ! Set(r => {read.add((i, r)); Some(i) }
                  , cbv => {callback.add((i, cbv)); latch.countDown() }
                  , false)
                Thread.sleep(1)
              case i =>
                a ! Set(r => {read.add((i, r)); None }
                  , cbv => {callback.add((i, cbv)); latch.countDown() }
                  , false)
                Thread.sleep(1)
            }
            failedCallback.addAll(awaitClose(a))
          }
      })({
        (res, read, callback, failedCallback, latch) =>

        //when `f` returns None (i is odd) next must see previous value
          val (_, expectedRead) =
            feed.foldLeft[(Option[Int], List[(Int, Option[Int])])]((None, Nil)) {
              case ((lastE, acc), i) if i % 2 == 0 => (Some(i), acc :+(i, lastE))
              case ((lastE, acc), i) => (lastE, acc :+(i, lastE))

            }

          //when `f` returns None (i is odd), the callback must see previous value, otherwise must be returned by `f` 
          val (_, expectedCallBack) =
            feed.foldLeft[(Option[Int], List[(Int, Throwable \/ Option[Int])])]((None, Nil)) {
              case ((lastE, acc), i) if i % 2 == 0 => (Some(i), acc :+(i, right(Some(i))))
              case ((lastE, acc), i) => (lastE, acc :+(i, right(lastE)))
            }

          (res == res.filter(_ % 2 == 0).distinct.sorted) :| "Stream has correct items (even only, distinct, sorted)" &&
            (serialChanges.filter(e => (e._1 == e._2)).isEmpty) :| "Get did not return with same serial" &&
            (expectedRead == read.toList) :| "Initial values are ok" &&
            (latch == 0L) :| "All callbacks were called" &&
            (expectedCallBack.sortBy(_._1) == callback.toList.sortBy(_._1)) :| "CallBacks are ok" &&
            (failedCallback == List(End)) :| "FailCallBack is ok"

      })


  }



  //checks it catch the exception on set and will `fail` the ref
  property("ref.catch-ex-on-set") = forAll {
    l: List[Int] =>

      val include = l.filter(_ % 2 == 0)
      val exclude = l.filterNot(_ % 2 == 0)

      @volatile var onFailRead: Option[Option[Int]] = None
      @volatile var onFailCallBack: Option[Throwable \/ Option[Int]] = None

      example[Int, Throwable \/ Int](l, include.size + exclude.size + 1)(s => s.attempt())({
        import message.ref._
        (a, read, callback, failedCallback, latch) =>
          Task {
            def feed(i: Int) = {
              a ! Set(r => {read.add((i, r)); Some(i) }
                , cbv => {callback.add((i, cbv)); latch.countDown() }
                , false)
              Thread.sleep(1)
            }
            include.foreach(feed)
            a ! Set(r => {onFailRead = Some(r); throw TestedEx }
              , cbv => {onFailCallBack = Some(cbv); latch.countDown() }
              , false)
            Thread.sleep(1)
            exclude.foreach(feed)
            failedCallback.addAll(awaitClose(a))
          }
      })({
        (res, read, callback, failedCallback, latch) =>

          res.collect { case \/-(r) => r }.forall(e => !exclude.contains(e)) :| "Stream was fed before exception" &&
            (res.lastOption == Some(-\/(TestedEx))) :| "Last collected is Exception" &&
            ((include zip (None +: include.map(Some(_)))) == read.toList) :| "Initial values are ok" &&
            (latch == 0L) :| "All callbacks were called" &&
            ((((include zip include.map(v => right(Some(v)))) ++
              (exclude zip exclude.map(v => left(TestedEx)))).sortBy(_._1) == callback.sortBy(_._1))) :| "CallBacks are ok" &&
            (onFailRead == Some(include.lastOption)) :| "Fail read value is ok" &&
            (onFailCallBack == Some(left(TestedEx))) :| "Fail callBack is ok" &&
            (failedCallback == List(TestedEx)) :| "End FailCallBack is ok"
      })


  }

}
