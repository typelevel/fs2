package fs2
package interop
package reactivestreams

import org.reactivestreams._
import cats.effect._

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext

import org.scalatest.funsuite.AnyFunSuite

/**
  * This behaviour is already tested by the Reactive Stream test
  * suite, but it's proven difficult to enforce, so we add our own
  * tests that run the assertions multiple times to make possible
  * failures due to race conditions more repeatable
  */
class CancellationSpec extends AnyFunSuite {
  implicit val ctx: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  case class Sub[A](b: AtomicBoolean) extends Subscriber[A] {
    def onNext(t: A) = b.set(true)
    def onComplete() = b.set(true)
    def onError(e: Throwable) = b.set(true)
    def onSubscribe(s: Subscription) = b.set(true)
  }

  val s = Stream.range(0, 5).covary[IO]

  val attempts = 10000

  test("after subscription is cancelled request must be noOps") {
    var i = 0
    val b = new AtomicBoolean(false)
    while (i < attempts) {
      val sub = StreamSubscription(Sub[Int](b), s).unsafeRunSync
      sub.unsafeStart
      sub.cancel
      sub.request(1)
      sub.request(1)
      sub.request(1)
      i = i + 1
    }
    if (b.get) fail("onNext was called after the subscription was cancelled")
  }

  test("after subscription is cancelled additional cancelations must be noOps") {
    var i = 0
    val b = new AtomicBoolean(false)
    while (i < attempts) {
      val sub = StreamSubscription(Sub[Int](b), s).unsafeRunSync
      sub.unsafeStart
      sub.cancel
      sub.cancel
      i = i + 1
    }
    if (b.get) fail("onCancel was called after the subscription was cancelled")
  }
}
