package fs2
package interop
package reactivestreams

import cats.effect._
import cats.implicits._

import java.nio.ByteBuffer
import org.reactivestreams._

import scala.concurrent.duration._

class SubscriberStabilitySpec extends Fs2Suite {
  test("StreamSubscriber has no race condition") {
    val realRunner: Runner[IO] = new Runner[IO]

    class TestRunner[F[_]: ConcurrentEffect: Timer](impl: Runner[F]) extends Runner[F] {
      private val rnd = new scala.util.Random()
      override def unsafeRunAsync[A](fa: F[A]): Unit = {
        val randomDelay =
          for {
            ms <- Sync[F].delay(rnd.nextInt(50))
            _ <- Timer[F].sleep(ms.millis)
          } yield ()
        impl.unsafeRunAsync(randomDelay *> fa)
      }
    }

    val testRunner: Runner[IO] = new TestRunner[IO](realRunner)

    val publisher = new Publisher[ByteBuffer] {

      class SubscriptionImpl(val s: Subscriber[_ >: ByteBuffer]) extends Subscription {
        override def request(n: Long): Unit = {
          s.onNext(ByteBuffer.wrap(new Array[Byte](1)))
          s.onComplete()
        }

        override def cancel(): Unit = {}
      }

      override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit =
        s.onSubscribe(new SubscriptionImpl(s))
    }

    val stream = Stream
      .eval(StreamSubscriber[IO, ByteBuffer](testRunner))
      .flatMap(s => s.sub.stream(IO.delay(publisher.subscribe(s))))

    val N = 100
    @volatile var failed: Boolean = false
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
      def uncaughtException(thread: Thread, error: Throwable): Unit =
        failed = true
    })

    def loop(remaining: Int): IO[Unit] =
      IO.delay(failed).flatMap { alreadyFailed =>
        if (!alreadyFailed) {
          val f = stream.compile.drain
          if (remaining > 0)
            f *> loop(remaining - 1)
          else
            f
        } else
          IO.unit
      }

    loop(N).unsafeRunSync()

    if (failed)
      fail("Uncaught exception was reported")
  }
}
