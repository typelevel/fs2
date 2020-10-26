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

package fs2.io

import cats.effect.IO
import fs2.{Chunk, Fs2Suite, Stream, Pure}

import org.scalacheck.Gen
import org.scalacheck.effect.PropF.forAllF

import scala.annotation.tailrec

class JavaInputOutputStreamSuite extends Fs2Suite {
  group("ToInputStream") {
    val streamByteGenerator: Gen[Stream[Pure, Byte]] =
      pureStreamGenerator[Chunk[Byte]]
        .arbitrary
        .map(_.flatMap(Stream.chunk))

    test("arbitrary streams") {
      forAllF(streamByteGenerator) { (stream: Stream[Pure, Byte]) =>
        val example = stream.compile.toVector

        toInputStreamResource(stream.covary[IO])
          .use { is =>
            // consume in same thread pool. Production application should never do this,
            // instead they have to fork this to dedicated thread pool
            IO {
              val buff = new Array[Byte](20)
              @tailrec
              def go(acc: Vector[Byte]): Vector[Byte] =
                is.read(buff) match {
                  case -1   => acc
                  case read => go(acc ++ buff.take(read))
                }
              go(Vector.empty)
            }
          }
          .assertEquals(example)
      }
    }

    test("upstream is closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
      IO.ref(false).flatMap { closed =>
        val s = Stream(1.toByte).onFinalize(closed.set(true))

        toInputStreamResource(s).use(_ => IO.unit) >> closed.get
      }.assertEquals(true)
    }

    test("upstream.is.force-closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
      IO.ref(false).flatMap { closed =>
        val s = Stream(1.toByte).onFinalize(closed.set(true))

        toInputStreamResource(s).use {is =>
          // verifies that, once close() terminates, upstream is cleaned up
          IO(is.close) >> closed.get
        }
      }.assertEquals(true)
    }

    test("converts to 0..255 int values except EOF mark") {
      val s = Stream
        .range(0, 256, 1)

      val expected = s.toVector :+ (-1)

      s
        .map(_.toByte)
        .covary[IO]
        .through(toInputStream)
        .flatMap(is => Stream.eval(IO(is.read)).repeatN(257))
        .compile
        .toVector
        .assertEquals(expected)
    }
  }
}
