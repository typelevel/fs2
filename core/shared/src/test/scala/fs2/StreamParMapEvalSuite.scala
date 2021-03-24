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
import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect.Clock
import org.scalacheck.effect.PropF.forAllF
import cats.effect.kernel.Deferred
// import org.scalacheck.Prop.exists
// import org.scalacheck.Prop.propBoolean

class StreamParMapEvalSuite extends Fs2Suite {

  private val ioThrow = IO.raiseError(new IllegalArgumentException)
  private def sleepAndEmit(i: Int) = IO.sleep(i.millis).as(i)
  private def sleepLimit10AndEmit(i: Int) = IO.sleep(math.abs(i % 10).millis).as(i)

  property("toList values") {
    test("paralleled .sorted equals") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .parEvalMapUnordered(Int.MaxValue)(sleepLimit10AndEmit)
          .compile
          .toList
          .map(_.sorted)
          .assertEquals(s.toList.sorted)
      }
    }

    // test("existsF shuffled, when concurrent") {
    //   exists { (s: Stream[Pure, Int]) =>
    //     s.toList.length > 1 ==>
    //       s.covary[IO]
    //         .parEvalMapUnordered(Int.MaxValue)(sleepLimit10AndEmit)
    //         .compile
    //         .toList
    //         .map(_ != s.toList)
    //         .assert
    //         .unsafeRunSync()
    //   }
    // }

    test("no parallelism - no shuffle") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .parEvalMapUnordered(1)(sleepLimit10AndEmit)
          .compile
          .toList
          .assertEquals(s.toList)
      }
    }
  }

  test("time reduces proportional to parallelism, when sleep + parEvalMapUnordered") {
    val s = Stream.constant(()).take(100).covary[IO]
    val io = s.parEvalMapUnordered(100)(_ => IO.sleep(100.millis)).compile.drain
    Clock[IO].timed(io).map(_._1.toMillis).map(dur => 100 < dur && dur < 500).assert
  }

  test("sorts by execution time") {
    val s = Stream(100, 50, 0, 0).covary[IO]
    s.parEvalMapUnordered(5)(sleepAndEmit).compile.toList.assertEquals(List(0, 0, 50, 100))
  }

  test("reads End Of Stream, but later an error occures - should result in error") {
    val sleep50 = sleepAndEmit(50)
    val raise25 = IO.sleep(25.millis) *> IO.raiseError(new IllegalArgumentException)

    val s = Stream(sleep50, sleep50, raise25).covary[IO].parEvalMapUnordered(4)(identity)
    s.compile.drain.intercept[IllegalArgumentException]
  }

  test("all that completed before before error should remain, after - cancelled") {
    Deferred[IO, Unit].flatMap { case (d1) =>
      val before = Stream(sleepAndEmit(100) <* d1.complete(()))
      val error = Stream(IO.sleep(80.millis) *> ioThrow)
      val after = Stream(60, 40).map(sleepAndEmit)
      val s = (before ++ error ++ after).covary[IO].parEvalMapUnordered(Int.MaxValue)(identity)

      s.compile.drain.intercept[IllegalArgumentException] *>
        (s.mask.compile.toList, d1.tryGet).mapN { case (masked, isCompleted) =>
          masked == List(40, 60) && isCompleted.isEmpty
        }.assert
    }
  }

  // Requires existsF
  // test("all errors in stream should combine to CompositeFailure") {
  //   def sleepF(i: Int) = IO.sleep(i.nanos) *> IO.raiseError(new IllegalArgumentException)
  //   def noSleepF(i: Int) = IO.raiseError(new IllegalArgumentException)

  //   def getStream(f: Int => IO[Unit]) =
  //     Stream.emits(10_000.to(1_000, -1))
  //       .covary[IO]
  //       .parEvalMapUnordered(Int.MaxValue)(f)
  //       .compile
  //       .drain
  //       .intercept[CompositeFailure]

  //   getStream(noSleepF)
  // }
}
