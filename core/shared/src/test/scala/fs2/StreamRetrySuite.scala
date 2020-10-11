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

class StreamRetrySuite extends Fs2Suite {

  case class RetryErr(msg: String = "") extends RuntimeException(msg)

  test("immediate success") {
    IO.suspend {
      var attempts = 0
      val job = IO {
        attempts += 1
        "success"
      }
      Stream.retry(job, 1.seconds, x => x, 100).compile.toList.map { r =>
        assert(attempts == 1)
        assert(r == List("success"))
      }
    }
  }

  test("eventual success") {
    IO.suspend {
      var failures, successes = 0
      val job = IO {
        if (failures == 5) {
          successes += 1; "success"
        } else {
          failures += 1; throw RetryErr()
        }
      }
      Stream.retry(job, 100.millis, x => x, 100).compile.toList.map { r =>
        assert(failures == 5)
        assert(successes == 1)
        assert(r == List("success"))
      }
    }
  }

  test("maxRetries") {
    IO.suspend {
      var failures = 0
      val job = IO {
        failures += 1
        throw RetryErr(failures.toString)
      }
      Stream.retry(job, 100.millis, x => x, 5).compile.drain.attempt.map {
        case Left(RetryErr(msg)) =>
          assert(failures == 5)
          assert(msg == "5")
        case _ => fail("Expected a RetryErr")
      }
    }
  }

  test("fatal") {
    IO.suspend {
      var failures, successes = 0
      val job = IO {
        if (failures == 5) {
          failures += 1; throw RetryErr("fatal")
        } else if (failures > 5) {
          successes += 1; "success"
        } else {
          failures += 1; throw RetryErr()
        }
      }
      val f: Throwable => Boolean = _.getMessage != "fatal"
      Stream.retry(job, 100.millis, x => x, 100, f).compile.drain.attempt.map {
        case Left(RetryErr(msg)) =>
          assert(failures == 6)
          assert(successes == 0)
          assert(msg == "fatal")
        case _ => fail("Expected a RetryErr")
      }
    }
  }

  test("delays".flaky) {
    val delays = scala.collection.mutable.ListBuffer.empty[Long]
    val unit = 200
    val maxTries = 5
    def getDelays =
      delays
        .synchronized(delays.toList)
        .sliding(2)
        .map(s => (s.tail.head - s.head) / unit)
        .toList

    val job = {
      val start = System.currentTimeMillis()
      IO {
        delays.synchronized(delays += System.currentTimeMillis() - start)
        throw RetryErr()
      }
    }

    Stream.retry(job, unit.millis, _ + unit.millis, maxTries).compile.drain.attempt.map {
      case Left(RetryErr(_)) =>
        assert(getDelays == List.range(1, maxTries))
      case _ => fail("Expected a RetryErr")
    }
  }
}
