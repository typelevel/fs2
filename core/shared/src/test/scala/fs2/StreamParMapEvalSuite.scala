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
import org.scalacheck.Prop.forAll
import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect.Clock

class StreamParMapEvalSuite extends Fs2Suite {

  private def sleepAndEmit(i: Int) = IO.sleep(i.millis).as(i)
  private def sleepLimit10AndEmit(i: Int) = IO.sleep(math.abs(i % 10).millis).as(i)

  property("whole stream should preserve size") {
    forAll { (s: Stream[Pure, Int], n: Int) =>
      val conc = (math.abs(n % 5)) + 1
      val io = s.covary[IO].parEvalMapUnordered(conc)(sleepLimit10AndEmit).compile.toList
      val result = io.unsafeRunSync()
      assertEquals(result.size, s.toList.size)
    }
  }

  property("no parallelism - no shuffle") {
    forAll { (s: Stream[Pure, Int]) =>
      val io = s.covary[IO].parEvalMapUnordered(1)(sleepLimit10AndEmit).compile.toList
      assertEquals(io.unsafeRunSync(), s.toList)
    }
  }

  // property("stream should get shuffled with n distance") { wrong!
  //   forAll { (s: Stream[Pure, Int], n: Int) =>
  //     val conc = (math.abs(n % 5)) + 1
  //     val io = s.covary[IO].parEvalMapUnordered(conc)(sleepLimit10AndEmit).compile.toList
  //     val result = io.unsafeRunSync()
  //     val list = s.toList
  //     assert(result.toList.zipWithIndex.forall { case(i, ind) => list.slice(ind - conc, ind + conc).contains(i) })
  //   }
  // }

  property("parallels one iteration") {
    val io = Stream
      .constant(())
      .take(100)
      .covary[IO]
      .parEvalMap(100)(_ => IO.sleep(100.millis))
      .compile
      .drain
    val dur = Clock[IO].timed(io).unsafeRunSync()._1.toMillis
    assert(100 < dur && dur < 1000)
  }

  test("sorts by execution time") {
    val s = Stream(100, 50, 0, 0).covary[IO]
    val result = s.parEvalMapUnordered(5)(sleepAndEmit).compile.toList.unsafeRunSync()
    assertEquals(result, List(0, 0, 50, 100))
  }

  test("all that launched before before error should remain") {
    val before = List(70, 60, 50).map(sleepAndEmit)
    val after = List(30, 20, 10).map(sleepAndEmit)
    val error = IO.sleep(10.millis) *> IO.raiseError(new IllegalArgumentException)
    val list = before :+ error :++ after
    val s = Stream.emits(list).covary[IO].parEvalMapUnordered(5)(identity)

    val recovered = s.compile.toList.recover(ex => List(-1)).unsafeRunSync()
    val masked = s.mask.compile.toList.unsafeRunSync()

    assertEquals(recovered, List(-1))
    // three options possible:
    val bool1 = masked == List(20, 30, 50, 60, 70)
    val bool2 = masked == List(30, 20, 50, 60, 70)
    val bool3 = masked == List(30, 50, 60, 70)

    assert(bool1 || bool2 || bool3)
  }

  // (++ should have weird bahavior?)
}
