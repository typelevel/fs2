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

import scala.concurrent.duration._

import cats.effect.IO
import cats.syntax.all._

class StreamRetrySuite extends Fs2Suite {

  case class RetryErr(msg: String = "") extends RuntimeException(msg)

  test("immediate success") {
    Counter[IO].flatMap { attempts =>
      val job = attempts.increment.as("success")

      Stream.retry(job, 1.seconds, x => x, 100)
        .compile
        .lastOrError
        .assertEquals("success") >> attempts.get.assertEquals(1L)
      }
    }

  test("eventual success") {
    (Counter[IO], Counter[IO]).tupled.flatMap {
      case (failures, successes) =>
        val job = failures.get.flatMap { n =>
          if (n == 5) successes.increment.as("success")
          else failures.increment >> IO.raiseError(RetryErr())
        }

        Stream.retry(job, 100.millis, x => x, 100)
          .compile
          .lastOrError
          .assertEquals("success") >>
        failures.get.assertEquals(5L) >>
        successes.get.assertEquals(1L)
    }
  }

  test("maxRetries") {
    Counter[IO].flatMap { failures =>
      val job = for {
        _ <- failures.increment
        v <- failures.get
        _ <- IO.raiseError[Unit](RetryErr(v.toString))
      } yield ()

      Stream.retry(job, 100.millis, x => x, 5)
        .compile
        .drain
        .map(_ => fail("Expected a RetryErr"))
        .recoverWith {
          case RetryErr(msg) =>
            failures.get.assertEquals(5L) >>
            IO(msg).assertEquals("5")
        }
    }
  }

  test("fatal") {
    (Counter[IO], Counter[IO]).tupled.flatMap {
      case (failures, successes) =>
        val job = failures.get.flatMap { n =>
          if (n == 5) failures.increment >> IO.raiseError(RetryErr("fatal"))
          else if (n > 5) successes.increment.as("success")
          else failures.increment >> IO.raiseError(RetryErr())
        }

      val f: Throwable => Boolean = _.getMessage != "fatal"

        Stream.retry(job, 100.millis, x => x, 100, f)
          .compile
          .drain
          .map(_ => fail("Expected a RetryErr"))
          .recoverWith {
            case RetryErr(msg) =>
              failures.get.assertEquals(6L) >>
              successes.get.assertEquals(0L) >>
              IO(msg).assertEquals("fatal")
        }
    }
  }

  test("delays") {
    IO.ref(List.empty[FiniteDuration]).flatMap { delays =>
      IO.monotonic.flatMap { start =>
        val unit = 200L
        val maxTries = 5
        val measuredDelays = delays.get.map {
          _.sliding(2)
            .map(s => (s.tail.head - s.head) / unit)
            .toList
        }
        val job = IO.monotonic.flatMap { t =>
          delays.update(_ :+ (t - start)) >> IO.raiseError(RetryErr())
        }
        val expected = List.range(1, maxTries).map(_.millis)

        Stream.retry(job, unit.millis, _ + unit.millis, maxTries)
          .compile
          .drain
          .map(_ => fail("Expected a RetryErr"))
          .recoverWith {
            case RetryErr(_) => measuredDelays.assertEquals(expected)
          }
      }
    }.ticked
  }
}
