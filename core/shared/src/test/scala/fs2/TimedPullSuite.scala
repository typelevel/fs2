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

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

import org.scalacheck.effect.PropF.forAllF

class TimedPullSuite extends Fs2Suite {
//TODO test case for stale timeout
  // source stream: wait 100millis then emit chunk than `never`
  // timed pull body:, start time 100 millis (to race with source) then, recursively
  // if left, record that
  // if right/ record that and start a very long timeout (to reset it but not get it)
  // interrupt resulting stream after some time, but not as long as the second timeout
  //  good outcomes:
  //   recorded chunk only
  //   recorded timeout and then chunk
  //  bug to repro if check is eliminated
  //   recorded chunk and then timeout: since the new timeout is far in the future,
  //   it means we received a stale timeout, which breaks the invariant that an old
  //   timeout can never be received after startTimer is called

  test("timed pull") {
    IO.unit
  }

  import Stream.TimedPull
  test("behaves as a normal Pull when no timeouts are used") {
    forAllF { (s: Stream[Pure, Int]) =>
      s.covary[IO].pull.timed { tp =>
        def loop(tp: TimedPull[IO, Int]): Pull[IO, Int, Unit] =
          tp.uncons.flatMap {
            case None => Pull.done
            case Some((Right(c), next)) => Pull.output(c) >> loop(next)
            case Some((Left(_), _)) => Pull.raiseError[IO](new Exception)
          }

        loop(tp)
      }.stream
        .compile
        .toList
        .map(it => assertEquals(it, s.compile.toList))
    }
  }

  test("pulls elements with timeouts, no timeouts trigger") {
    // TODO cannot use PropF with `.ticked` at the moment
    val l = List.range(1, 500)
    val s = Stream.emits(l).covary[IO].rechunkRandomly()
    val period = 500.millis
    val timeout = 600.millis

    s.metered(period).pull.timed { tp =>
      def loop(tp: TimedPull[IO, Int]): Pull[IO, Int, Unit] =
        tp.uncons.flatMap {
          case None => Pull.done
          case Some((Right(c), next)) => Pull.output(c) >> tp.startTimer(timeout)  >> loop(next)
          case Some((Left(_), _)) => Pull.raiseError[IO](new TimeoutException)
        }

      tp.startTimer(timeout) >> loop(tp)
    }.stream
      .compile
      .toList
      .map(it => assertEquals(it, l))
      .ticked
  }

  test("times out whilst pulling a single element") {
    Stream.eval(IO.sleep(300.millis).as(1))
      .pull
      .timed { tp =>
        tp.startTimer(100.millis) >>
        tp.uncons.flatMap {
          case Some((Left(_), _)) => Pull.done
          case _ => Pull.raiseError[IO](new Exception("timeout expected"))
        }
      }.stream.compile.drain
  }

  // -- based on a stream emitting multiple elements, with metered
  // pull single element, timeout
  // pull multiple elements, timeout reset, with timeout
  // try pull multiple elements with no timeout reset, with timeout
}
