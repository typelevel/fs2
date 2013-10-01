package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.\/
import scala.concurrent.SyncVar
import scalaz.concurrent.Task
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

      val topic = async.topic[Int]


      val sub1 = new SyncVar[Throwable \/ Seq[Int]]
      val sub2 = new SyncVar[Throwable \/ Seq[Int]]
      val sub3 = new SyncVar[Throwable \/ Seq[Int]]
      val sub4 = new SyncVar[Throwable \/ Seq[Int]]

      Task(topic.subscribe.collect.runAsync(sub1.put)).run
      Thread.sleep(10)
      Task(topic.subscribe.collect.runAsync(sub2.put)).run
      Thread.sleep(10)
      Task(topic.subscribe.collect.runAsync(sub3.put)).run
      Thread.sleep(10)
      Task(topic.subscribe.collect.runAsync(sub4.put)).run
      Thread.sleep(10)

      val pubOdd = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(odd).evalMap(Task.now(_)) to topic.publish).run.runAsync(pubOdd.put)).run

      val pubEven = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(even).evalMap(Task.now(_)) to topic.publish).run.runAsync(pubEven.put)).run

      val oddResult = pubOdd.get(3000)
      val evenResult = pubEven.get(3000)

      topic.close.run
 

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
        (verifySub(sub1, "4"))  


  }





  //tests once failed all the publishes, subscribers and signals will fail too
  property("fail") = forAll {
    l: List[Int] =>
      (l.size > 0 && l.size < 10000) ==> {
        val topic = async.topic[Int]
        topic.fail(TestedEx).run
 

        val emitted = new SyncVar[Throwable \/ Unit]
        Task((Process.emitAll(l).evalMap(Task.now(_)) to topic.publish).run.runAsync(emitted.put)).run

        val sub1 = new SyncVar[Throwable \/ Seq[Int]]

        Task(topic.subscribe.collect.runAsync(sub1.put)).run


        emitted.get(3000) 
        sub1.get(3000)


        (emitted.get(0).nonEmpty && emitted.get == -\/(TestedEx)) :| "publisher fails" && 
          (sub1.get(0).nonEmpty && sub1.get == -\/(TestedEx))     :| "subscriber fails"
      }
  }


}
