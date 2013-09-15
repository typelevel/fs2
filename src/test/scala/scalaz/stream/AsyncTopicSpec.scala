package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.\/
import scala.concurrent.SyncVar
import scalaz.concurrent.Task
import processes._
import java.lang.Exception
import scala.Predef._
import scalaz.-\/

object AsyncTopicSpec extends Properties("topic") {

  case object TestedEx extends Exception("expected in test") {
    override def fillInStackTrace = this
  }


  //tests basic publisher and subscriber functionality 
  //have two publishers and four subscribers. 
  //each publishers emits own half of elements (odd and evens) and all subscribers must 
  //get that messages
  property("basic") = forAll {
    l: List[Int] =>
      val (even, odd) = (l.filter(_ % 2 == 0), l.filter(_ % 2 != 0))

      val topic = async.topic[Int, String]

      val signal = new SyncVar[Throwable \/ Seq[List[String]]]
      Task(topic.subscribers.discrete.collect.runAsync(signal.put)).run

      val sub1 = new SyncVar[Throwable \/ Seq[Int]]
      val sub2 = new SyncVar[Throwable \/ Seq[Int]]
      val sub3 = new SyncVar[Throwable \/ Seq[Int]]
      val sub4 = new SyncVar[Throwable \/ Seq[Int]]

      Task(topic.subscriber("1").collect.runAsync(sub1.put)).run
      Thread.sleep(10)
      Task(topic.subscriber("2").collect.runAsync(sub2.put)).run
      Thread.sleep(10)
      Task(topic.subscriber("3").collect.runAsync(sub3.put)).run
      Thread.sleep(10)
      Task(topic.subscriber("4").collect.runAsync(sub4.put)).run
      Thread.sleep(10)

      val pubOdd = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(odd).evalMap(Task.now(_)) to topic.publisher).run.runAsync(pubOdd.put)).run

      val pubEven = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(even).evalMap(Task.now(_)) to topic.publisher).run.runAsync(pubEven.put)).run

      val oddResult = pubOdd.get(3000)
      val evenResult = pubEven.get(3000)

      topic.close.run

      val signalResult = signal.get(3000)

      def verifySub(sub: SyncVar[Throwable \/ Seq[Int]], subId: String) = {
        val result = sub.get(3000)

        (result.nonEmpty && result.get.isRight                         :| s"Subscriber $subId finished") &&
          ((result.get.toOption.get.size == l.size)                    :| s"Subscriber $subId got all numbers") &&
          ((result.get.toOption.get.filter(_ % 2 == 0) == even)        :| s"Subscriber $subId got all even numbers") &&
          ((result.get.toOption.get.filter(_ % 2 != 0) == odd)         :| s"Subscriber $subId got all odd numbers")

      }


      (oddResult.nonEmpty && oddResult.get.isRight                                   :| "Odd numbers were published") &&
        (evenResult.nonEmpty && evenResult.get.isRight                               :| "Even numbers were published") &&
        (verifySub(sub1, "1")) &&
        (verifySub(sub1, "2")) &&
        (verifySub(sub1, "3")) &&
        (verifySub(sub1, "4")) &&
        (signalResult.nonEmpty && signalResult.get.isRight)                          :| "Signal of subscribers was ok" &&
        (signalResult.get.toOption.get.lastOption == Some(List("1", "2", "3", "4"))) :| "Signal of subscribers content is ok"


  }


  //tests if the subscribers when subscribed appear on the signal 
  //and when unsubscribed they do disappear
  property("subsribe-unsubscribe") = forAll {
    l: List[Int] =>
      (l.size > 0 && l.size < 10000) ==> {

        val topic = async.topic[Int, Int]

        val signal = new SyncVar[Throwable \/ Seq[List[Int]]]
        Task((topic.subscribers.discrete |> takeWhile[List[Int]](_.nonEmpty)).collect.runAsync(signal.put)).run

        val syncs =
          l.map {
            i =>
              val sync = new SyncVar[Throwable \/ Seq[Int]]
              Task((topic.subscriber(i) |> takeWhile[Int](_ != i)).collect.runAsync(sync.put)).run
              Thread.sleep(10)
              (i, sync)
          }

        Thread.sleep(100)

        val emitted = new SyncVar[Throwable \/ Unit]
        Task((Process.emitAll(l).evalMap(Task.now(_)) to topic.publisher).run.runAsync(emitted.put)).run

        val result = emitted.get(3000)

        syncs.foreach(s => s._2.get(3000))

        val signalResult = signal.get(3000)
        topic.close

        (result.nonEmpty && result.get.isRight                             :| "Vales were published") &&
          (syncs.forall(s => s._2.get(3000).isDefined && s._2.get.isRight) :| "Subscribers were terminated ok") &&
          (signalResult.nonEmpty && signal.get.isRight)                    :| "Signal is terminated ok"
      }


  }



  //tests once failed all the publishes, subscribers and signals will fail too
  property("fail") = forAll {
    l: List[Int] =>
      (l.size > 0 && l.size < 10000) ==> {
        val topic = async.topic[Int, Int]
        topic.fail(TestedEx).run

        val signal = new SyncVar[Throwable \/ Seq[List[Int]]]
        Task((topic.subscribers.discrete).collect.runAsync(signal.put)).run

        val emitted = new SyncVar[Throwable \/ Unit]
        Task((Process.emitAll(l).evalMap(Task.now(_)) to topic.publisher).run.runAsync(emitted.put)).run

        val sub1 = new SyncVar[Throwable \/ Seq[Int]]

        Task(topic.subscriber(1).collect.runAsync(sub1.put)).run


        emitted.get(3000)
        signal.get(3000)
        sub1.get(3000)


        (emitted.get(0).nonEmpty && emitted.get == -\/(TestedEx)) :| "publisher fails" &&
          (signal.get(0).nonEmpty && signal.get == -\/(TestedEx)) :| "signal fails" &&
          (sub1.get(0).nonEmpty && sub1.get == -\/(TestedEx))     :| "subscriber fails"
      }
  }


}
