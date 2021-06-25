/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package interop
package reactivestreams

import cats.effect._

import java.nio.ByteBuffer
import org.reactivestreams._

import scala.concurrent.duration._

class SubscriberStabilitySpec extends Fs2Suite {
  test("StreamSubscriber has no race condition") {
    val io = ConcurrentEffect[IO]
    val testConcurrentEffect: ConcurrentEffect[IO] = new ConcurrentEffect[IO] {
      private val rnd = new scala.util.Random()

      private val randomDelay: IO[Unit] =
        for {
          ms <- IO.delay(rnd.nextInt(50))
          _ <- Timer[IO].sleep(ms.millis)
        } yield ()

      // Injecting random delay
      override def runCancelable[A](fa: IO[A])(
          cb: Either[Throwable, A] => IO[Unit]
      ): SyncIO[CancelToken[IO]] =
        io.runCancelable(randomDelay *> fa)(cb)

      // Injecting random delay
      override def runAsync[A](fa: IO[A])(
          cb: scala.util.Either[Throwable, A] => IO[Unit]
      ): SyncIO[Unit] =
        io.runAsync(randomDelay *> fa)(cb)

      // All the other methods just delegating to io
      def pure[A](x: A): IO[A] = io.pure(x)
      def handleErrorWith[A](fa: IO[A])(
          f: Throwable => IO[A]
      ): IO[A] = io.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): IO[A] = io.raiseError(e)
      def async[A](k: (scala.util.Either[Throwable, A] => Unit) => Unit): IO[A] =
        io.async(k)
      def asyncF[A](
          k: (scala.util.Either[Throwable, A] => Unit) => IO[Unit]
      ): IO[A] = io.asyncF(k)
      def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(
          release: (A, ExitCase[Throwable]) => IO[Unit]
      ): IO[B] =
        io.bracketCase(acquire)(use)(release)
      def racePair[A, B](
          fa: IO[A],
          fb: IO[B]
      ): IO[scala.util.Either[
        (A, Fiber[IO, B]),
        (Fiber[IO, A], B)
      ]] =
        io.racePair(fa, fb)
      def start[A](fa: IO[A]): IO[Fiber[IO, A]] =
        io.start(fa)
      def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
        io.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
        io.tailRecM(a)(f)
      def suspend[A](thunk: => IO[A]): IO[A] =
        io.defer(thunk)
    }

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

    val stream = fromPublisher(publisher)(testConcurrentEffect)

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
