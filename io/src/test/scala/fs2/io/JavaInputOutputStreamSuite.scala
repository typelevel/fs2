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
import fs2.{Chunk, Fs2Suite, Stream}
import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll

import scala.annotation.tailrec

class JavaInputOutputStreamSuite extends Fs2Suite {
  group("ToInputStream") {
    implicit val streamByteGenerator: Arbitrary[Stream[IO, Byte]] = Arbitrary {
      for {
        chunks <- pureStreamGenerator[Chunk[Byte]].arbitrary
      } yield chunks.flatMap(Stream.chunk).covary[IO]
    }

    property("arbitrary.streams") {
      forAll { (stream: Stream[IO, Byte]) =>
        val example = stream.compile.toVector.unsafeRunSync()

        val fromInputStream =
          toInputStreamResource(stream)
            .use { is =>
              // consume in same thread pool. Production application should never do this,
              // instead they have to fork this to dedicated thread pool
              val buff = new Array[Byte](20)
              @tailrec
              def go(acc: Vector[Byte]): IO[Vector[Byte]] =
                is.read(buff) match {
                  case -1   => IO.pure(acc)
                  case read => go(acc ++ buff.take(read))
                }
              go(Vector.empty)
            }
            .unsafeRunSync()

        assert(example == fromInputStream)
      }
    }

    test("upstream.is.closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
      var closed: Boolean = false
      val s: Stream[IO, Byte] =
        Stream(1.toByte).onFinalize(IO { closed = true })

      toInputStreamResource(s).use(_ => IO.unit).unsafeRunSync()

      // eventually...
      assert(closed)
    }

    test("upstream.is.force-closed".ignore) {
      // https://github.com/functional-streams-for-scala/fs2/issues/1063
      var closed: Boolean = false
      val s: Stream[IO, Byte] =
        Stream(1.toByte).onFinalize(IO { closed = true })

      val result =
        toInputStreamResource(s)
          .use { is =>
            IO {
              is.close()
              closed // verifies that once close() terminates upstream was already cleaned up
            }
          }
          .unsafeRunSync()

      assert(result)
    }

    test("converts to 0..255 int values except EOF mark") {
      Stream
        .range(0, 256, 1)
        .map(_.toByte)
        .covary[IO]
        .through(toInputStream)
        .map(is => Vector.fill(257)(is.read()))
        .compile
        .toVector
        .map(_.flatten)
        .map(it => assert(it == (Stream.range(0, 256, 1) ++ Stream(-1)).toVector))
    }
  }
}
