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
import fs2.text.utf8
import org.scalacheck.effect.PropF.forAllF

import java.io.ByteArrayInputStream
import java.io.InputStream

class IoSuite extends Fs2Suite {
  group("readInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = io.readInputStream(IO(is), chunkSize)
        stream.compile.toVector.assertEquals(bytes.toVector)
      }
    }

    test("buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = io.readInputStream(IO(is), chunkSize)
        stream
          .buffer(chunkSize * 2)
          .compile
          .toVector
          .assertEquals(bytes.toVector)
      }
    }
  }

  group("unsafeReadInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = io.unsafeReadInputStream(IO(is), chunkSize)
        stream.compile.toVector.assertEquals(bytes.toVector)
      }
    }
  }

  group("standard streams") {
    test("stdout") {
      Stream
        .emit("good day stdout\n")
        .through(utf8.encode)
        .through(io.stdout[IO])
        .compile
        .drain
    }

    test("stderr") {
      Stream
        .emit("how do you do stderr\n")
        .through(utf8.encode)
        .through(io.stderr[IO])
        .compile
        .drain
    }
  }
}
