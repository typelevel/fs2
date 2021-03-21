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
package concurrent

import cats.syntax.all._
import cats.effect.IO
import scala.concurrent.duration._

import org.scalacheck.effect.PropF.forAllF

class ChannelSuite extends Fs2Suite {
  test("Channel receives all elements and closes") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.unbounded[IO, Int].flatMap { chan =>
        chan.stream
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Slow consumer doesn't lose elements") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.unbounded[IO, Int].flatMap { chan =>
        chan.stream
          .metered(1.millis)
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Blocked producers get dequeued") {
    forAllF { (source: Stream[Pure, Int]) =>
      Channel.synchronous[IO, Int].flatMap { chan =>
        chan.stream
          .metered(1.milli)
          .concurrently {
            source
              .covary[IO]
              .rechunkRandomly()
              .through(chan.sendAll)
          }
          .compile
          .toVector
          .assertEquals(source.compile.toVector)
      }
    }
  }

  test("Queued elements arrive in a single chunk") {
    val v = Vector(1, 2, 3)
    val p = for {
      chan <- Channel.unbounded[IO, Int]
      _ <- v.traverse(chan.send)
      _ <- chan.close
      res <- chan.stream.chunks.take(1).compile.lastOrError
    } yield res.toVector

    p.assertEquals(v)
  }

  test("Timely closure") {
    val v = Vector(1, 2, 3)
    val p = for {
      chan <- Channel.unbounded[IO, Int]
      _ <- v.traverse(chan.send)
      _ <- chan.close
      _ <- v.traverse(chan.send)
      res <- chan.stream.compile.toVector
    } yield res

    p.assertEquals(v)
  }

}
