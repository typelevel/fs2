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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2
package timeseries

import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import scodec.bits._

import munit.Location

import TimeStamped.syntax._

class TimeStampedSuite extends Fs2Suite {

  def assertEqualsEpsilon(actual: Double, expected: Double, epsilon: Double)(implicit
      loc: Location
  ): Unit =
    assert((actual >= expected - epsilon) && (actual <= expected + epsilon))

  def assertEqualsEpsilon(actual: Long, expected: Long, epsilon: Long)(implicit
      loc: Location
  ): Unit =
    assertEqualsEpsilon(actual.toDouble, expected.toDouble, epsilon.toDouble)

  group("rates") {

    test(
      "emits accumulated feature values for each specified time period and emits a final value"
    ) {
      val data = Stream(1.at(0.seconds), 2.at(0.5.seconds), 1.at(1.seconds), 2.at(2.3.seconds))

      assertEquals(
        data.through(TimeStamped.rate(1.second)(identity[Int]).toPipe).toVector,
        Vector(3.at(1.second), 1.at(2.seconds), 2.at(3.seconds))
      )

      assertEquals(
        data.through(TimeStamped.rate(2.seconds)(identity[Int]).toPipe).toVector,
        Vector(4.at(2.seconds), 2.at(4.seconds))
      )
    }

    test("emits 0s when values are skipped over") {
      val data = Stream(1.at(0.seconds), 2.at(3.3.seconds))
      assertEquals(
        data.through(TimeStamped.rate(1.second)(identity[Int]).toPipe).toVector,
        Vector(1.at(1.second), 0.at(2.seconds), 0.at(3.seconds), 2.at(4.seconds))
      )

      assertEquals(
        data.through(TimeStamped.withRate(1.second)(identity[Int]).toPipe).toVector,
        Vector(
          Right(1) at 0.seconds,
          Left(1) at 1.second,
          Left(0) at 2.seconds,
          Left(0) at 3.seconds,
          Right(2) at 3.3.seconds,
          Left(2) at 4.seconds
        )
      )
    }

    test("supports calculation of an average bitrate") {
      val data = Stream(
        hex"deadbeef".at(0.seconds),
        hex"deadbeef".at(1.seconds),
        hex"deadbeef".at(1.5.seconds),
        hex"deadbeef".at(2.5.seconds),
        hex"deadbeef".at(2.6.seconds)
      )

      val bitsPerSecond =
        data.through(TimeStamped.rate(1.second)((x: ByteVector) => x.size * 8L).toPipe)

      case class Average(samples: Int, value: Double)
      val zero = Average(0, 0)
      val combineAverages = (x: Average, y: Average) => {
        val totalSamples = x.samples + y.samples
        val avg = ((x.samples * x.value) + (y.samples * y.value)) / totalSamples
        Average(totalSamples, avg)
      }

      val avgBitrate = bitsPerSecond.toVector.foldLeft(zero) { (acc, bits) =>
        combineAverages(acc, Average(1, bits.value.toDouble))
      }
      assertEqualsEpsilon(avgBitrate.value, 53.3, 1.0)
    }
  }

  group(
    "support filtering a source of timestamped values such that output is monotonically increasing in time"
  ) {
    def ts(value: Int) = TimeStamped(value.seconds, ())
    val data = Stream(0, -2, -1, 1, 5, 3, 6).map(ts)

    test("supports dropping out-of-order values") {
      val filtered = data.through(TimeStamped.increasing)
      assertEquals(filtered.toList, List(ts(0), ts(1), ts(5), ts(6)))
    }

    test("supports receiving out-of-order values") {
      val filtered = data.through(TimeStamped.increasingEither)
      assertEquals(
        filtered.toList,
        List(
          Right(ts(0)),
          Left(ts(-2)),
          Left(ts(-1)),
          Right(ts(1)),
          Right(ts(5)),
          Left(ts(3)),
          Right(ts(6))
        )
      )
    }
  }

  group(
    "support reordering timestamped values over a specified time buffer such that output is monotonically increasing in time"
  ) {
    def ts(value: Int) = TimeStamped(value.millis, value.toLong)

    val onTheSecond = Stream.emits(1 to 10).map(x => ts(x * 1000))
    val onTheQuarterPast = onTheSecond.map(_.mapTime(t => t + 250.millis))

    test("reorders when all out of order values lie within the buffer time") {
      val inOrder = onTheSecond.interleave(onTheQuarterPast)
      val outOfOrder = onTheQuarterPast.interleave(onTheSecond)
      val reordered = outOfOrder.through(TimeStamped.reorderLocally(1.second))
      assertEquals(reordered.toList, inOrder.toList)
    }

    test("drops values that appear outside the buffer time") {
      // Create mostly ordered data with clumps of values around each second that are unordered
      val events = Stream.emits(1 to 10).flatMap { x =>
        val local = (-10 to 10).map(y => ts((x * 1000) + (y * 10)))
        Stream.emits(scala.util.Random.shuffle(local))
      }
      val reordered200ms = events.through(TimeStamped.reorderLocally(200.milliseconds))
      assertEquals(reordered200ms.toList, events.toList.sorted)

      val reordered20ms = events.through(TimeStamped.reorderLocally(20.milliseconds))
      assert(reordered20ms.toList.size >= 10)
    }

    test("emits values with the same timestamp in insertion order") {
      val onTheSecondBumped = onTheSecond.map(_.map(_ + 1))
      val inOrder = (onTheSecond
        .interleave(onTheQuarterPast))
        .interleave(onTheSecondBumped.interleave(onTheQuarterPast))
      val outOfOrder = (onTheQuarterPast
        .interleave(onTheSecond))
        .interleave(onTheQuarterPast.interleave(onTheSecondBumped))
      val reordered = outOfOrder.through(TimeStamped.reorderLocally(1.second))
      assertEquals(reordered.toList, inOrder.toList)
    }
  }

  test("support throttling a time stamped source") {
    def ts(value: Int) = TimeStamped(value.seconds, value.toLong)
    val source = Stream(ts(0), ts(1), ts(2), ts(3), ts(4)).covary[IO]
    def time[A](f: IO[A]): IO[Long] =
      IO.delay(System.nanoTime()).flatMap { started =>
        f >> IO.delay(System.nanoTime() - started)
      }
    val realtime = source.through(TimeStamped.throttle[IO, Long](1.0)).compile.drain
    val doubletime = source.through(TimeStamped.throttle[IO, Long](2.0)).compile.drain

    time(realtime).map { elapsed =>
      assertEqualsEpsilon(elapsed, 4.seconds.toNanos, 250.millis.toNanos)
    } >>
      time(doubletime).map { elapsed =>
        assertEqualsEpsilon(elapsed, 2.seconds.toNanos, 250.millis.toNanos)
      }
  }

  test(
    "support lifting a Scan[S, TimeStamped[A], TimeStamped[B]] in to a Scan[S, TimeStamped[Either[A, C]], TimeStamped[Either[B, C]]]"
  ) {
    val source = Stream(
      Left(1) at 1.millis,
      Right(2) at 2.millis,
      Right(3) at 3.millis,
      Left(4) at 4.millis,
      Left(5) at 5.millis,
      Right(6) at 6.millis
    )
    val square: Scan[Unit, TimeStamped[Int], TimeStamped[Int]] = Scan.lift(_.map(x => x * x))
    assertEquals(
      source.through(TimeStamped.left(square).toPipe).toVector,
      Vector(
        Left(1) at 1.millis,
        Right(2) at 2.millis,
        Right(3) at 3.millis,
        Left(16) at 4.millis,
        Left(25) at 5.millis,
        Right(6) at 6.millis
      )
    )
  }
}
