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
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.concurrent.duration._

class StreamConflateSuite extends Fs2Suite {

  test("conflateMap") {
    TestControl.executeEmbed(
      Stream
        .iterate(0)(_ + 1)
        .covary[IO]
        .metered(10.millis)
        .conflateMap(100)(List(_))
        .metered(101.millis)
        .take(5)
        .compile
        .toList
        .assertEquals(
          List(0) :: (1 until 10).toList :: 10.until(40).toList.grouped(10).toList
        )
    )
  }
  test("conflateChunks respects chunk limit") {

    (1 to 1000).toList.traverse_ { _ =>
      Stream(1, 2, 3, 4, 5, 6, 7)
        .covary[IO]
        .chunkLimit(1)
        .unchunks
        .conflateChunks(3)
        .compile
        .toList
        .map { chunks =>
          assert(
            chunks.forall(_.size <= 3),
            s"Expected all chunks <= 3, but got: $chunks"
          )
        }
    }
  }

}
