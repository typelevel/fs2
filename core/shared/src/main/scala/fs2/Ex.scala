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

object Ex {
  import cats.effect.IO
  import cats.effect.unsafe.implicits.global
  import scala.concurrent.duration._

  def e = {
    Stream 
      .range(0, 10)
      .covary[IO]
      .broadcastThrough[IO, Int](
        _.filter(_ % 2 == 0).debug(v => s"even $v"),
        _.filter(_ % 2 != 0).debug(v => s"odd $v"),
        _.debug(v => s"id $v").interruptAfter(2.nanos)
      )
      .compile
      .drain
      .flatMap(_ => IO.println("done"))
      .onCancel(IO.println("deadlocked"))
  }
    .timeoutTo(5.seconds, IO.println("timeout")).unsafeToFuture()

}
