package fs2
package interop
package reactivestreams

import cats.effect._
import org.reactivestreams._
import org.reactivestreams.tck.{PublisherVerification, TestEnvironment}
import org.scalatestplus.testng._

final class FailedSubscription(sub: Subscriber[_]) extends Subscription {
  def cancel(): Unit = {}
  def request(n: Long): Unit = {}
}

final class FailedPublisher extends Publisher[Int] {
  def subscribe(subscriber: Subscriber[_ >: Int]): Unit = {
    subscriber.onSubscribe(new FailedSubscription(subscriber))
    subscriber.onError(new Error("BOOM"))
  }
}

final class StreamUnicastPublisherSpec
    extends PublisherVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  implicit val ctx: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  def createPublisher(n: Long): StreamUnicastPublisher[IO, Int] = {
    val s =
      if (n == java.lang.Long.MAX_VALUE) Stream.range(1, 20).repeat
      else Stream(1).repeat.scan(1)(_ + _).map(i => if (i > n) None else Some(i)).unNoneTerminate

    s.covary[IO].toUnicastPublisher
  }

  def createFailedPublisher(): FailedPublisher = new FailedPublisher()
}
