package fs2
package interop
package reactivestreams

import java.util.concurrent.atomic.AtomicInteger

import cats.effect._
import cats.implicits._
import org.reactivestreams._
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{
  SubscriberPuppet,
  WhiteboxSubscriberProbe
}
import org.reactivestreams.tck.{
  SubscriberBlackboxVerification,
  SubscriberWhiteboxVerification,
  TestEnvironment
}
import org.scalatestplus.testng.TestNGSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class SubscriberWhiteboxSpec
    extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  implicit val ctx: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  private val counter = new AtomicInteger()

  def createSubscriber(
      p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]
  ): Subscriber[Int] =
    StreamSubscriber[IO, Int]
      .map { s =>
        new WhiteboxSubscriber(s, p)
      }
      .unsafeRunSync()

  def createElement(i: Int): Int = counter.getAndIncrement
}

final class WhiteboxSubscriber[A](sub: StreamSubscriber[IO, A], probe: WhiteboxSubscriberProbe[A])
    extends Subscriber[A] {

  def onError(t: Throwable): Unit = {
    sub.onError(t)
    probe.registerOnError(t)
  }

  def onSubscribe(s: Subscription): Unit = {
    sub.onSubscribe(s)
    probe.registerOnSubscribe(new SubscriberPuppet {
      override def triggerRequest(elements: Long): Unit =
        (0 to elements.toInt)
          .foldLeft(IO.unit)((t, _) => t.flatMap(_ => sub.sub.dequeue1.map(_ => ())))
          .unsafeRunAsync(_ => ())

      override def signalCancel(): Unit =
        s.cancel()
    })
  }

  def onComplete(): Unit = {
    sub.onComplete()
    probe.registerOnComplete()
  }

  def onNext(a: A): Unit = {
    sub.onNext(a)
    probe.registerOnNext(a)
  }
}

final class SubscriberBlackboxSpec
    extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  val timer = IO.timer(ExecutionContext.global)
  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private val counter = new AtomicInteger()

  def createSubscriber(): StreamSubscriber[IO, Int] = StreamSubscriber[IO, Int].unsafeRunSync()

  override def triggerRequest(s: Subscriber[_ >: Int]): Unit = {
    val req = s.asInstanceOf[StreamSubscriber[IO, Int]].sub.dequeue1
    (Stream.eval(timer.sleep(100.milliseconds) >> req)).compile.drain.unsafeRunAsync(_ => ())
  }

  def createElement(i: Int): Int = counter.incrementAndGet()
}
