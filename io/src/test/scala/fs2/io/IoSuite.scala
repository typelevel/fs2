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
package io

import cats.effect.{Blocker, IO, Resource}
import fs2.Fs2Suite
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.ExecutionContext

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class IoSuite extends Fs2Suite {
  group("readInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.map(it => assertEquals(it, bytes.toVector))
        }
      }
    }

    test("buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream
            .buffer(chunkSize * 2)
            .compile
            .toVector
            .map(it => assertEquals(it, bytes.toVector))
        }
      }
    }
  }

  group("readOutputStream") {
    test("writes data and terminates when `f` returns") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        Blocker[IO].use { blocker =>
          readOutputStream[IO](blocker, chunkSize)((os: OutputStream) =>
            blocker.delay[IO, Unit](os.write(bytes))
          ).compile
            .to(Vector)
            .map(it => assertEquals(it, bytes.toVector))
        }
      }
    }

    test("can be manually closed from inside `f`") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        Blocker[IO].use { blocker =>
          readOutputStream[IO](blocker, chunkSize)((os: OutputStream) =>
            blocker.delay(os.close()) *> IO.never
          ).compile.toVector
            .map(it => assert(it == Vector.empty))
        }
      }
    }

    test("fails when `f` fails") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val e = new Exception("boom")
        Blocker[IO].use { blocker =>
          readOutputStream[IO](blocker, chunkSize)((_: OutputStream) =>
            IO.raiseError(e)
          ).compile.toVector.attempt
            .map(it => assert(it == Left(e)))
        }
      }
    }

    test("Doesn't deadlock with size-1 ContextShift thread pool") {
      val pool =
        Resource
          .make(IO(Executors.newSingleThreadExecutor()))(e => IO(e.shutdown()))
          .map(ExecutionContext.fromExecutor)
          .map(IO.contextShift)

      def write(blocker: Blocker, os: OutputStream): IO[Unit] =
        blocker.delay {
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
        }

      val poolBlockerResource =
        for {
          p <- pool
          b <- Blocker[IO]
        } yield (p, b)

      poolBlockerResource.use { case (pool, blocker) =>
        implicit val munitContextShift = pool

        readOutputStream[IO](blocker, chunkSize = 1)(write(blocker, _))
          .take(5)
          .compile
          .toVector
          .map(_.size)
          .assertEquals(5)
      }
    }

    test("emits chunks of the configured size") {
      case class ChunkSize(value: Int)
      val defaultPipedInputStreamBufferSize = 1024 // private in PipedInputStream.DEFAULT_PIPE_SIZE
      implicit val arbChunkSize: Arbitrary[ChunkSize] = Arbitrary {
        Gen.chooseNum(defaultPipedInputStreamBufferSize + 1, 65536).map(ChunkSize(_))
      }
      implicit val shrinkChunkSize: Shrink[ChunkSize] =
        Shrink.xmap[Int, ChunkSize](ChunkSize(_), _.value) {
          Shrink.shrinkIntegral[Int].suchThat(_ > defaultPipedInputStreamBufferSize)
        }

      forAllF { (chunkSize: ChunkSize) =>
        val bytes: Array[Byte] =
          fs2.Stream.emit(0: Byte).repeat.take((chunkSize.value + 1).toLong).compile.to(Array)

        Blocker[IO].use { blocker =>
          readOutputStream[IO](blocker, chunkSize.value) { (os: OutputStream) =>
            blocker.delay[IO, Unit](os.write(bytes))
          }.chunks.head.compile.lastOrError
            .map(chunk => assertEquals(chunk.size, chunkSize.value))
        }
      }
    }

    test("PipedInput/OutputStream used to track threads, fs2 reimplementation works") {
      Blocker[IO].use { blocker =>
        readOutputStream(blocker, 1024) { os =>
          blocker.delay {
            val t = new Thread(() => os.write(123))
            t.start
            t.join
            Thread.sleep(100L)
          }
        }.compile.drain.map(_ => assert(true))
      }
    }

    test("different chunk sizes function correctly") {

      def test(blocker: Blocker, chunkSize: Int): Pipe[IO, Byte, Byte] = source => {
        readOutputStream(blocker, chunkSize) { os =>
          source.through(writeOutputStream(IO.delay(os), blocker, true)).compile.drain
        }
      }

      def source(chunkSize: Int, bufferSize: Int): Stream[Pure, Byte] =
        Stream.range(65, 75).map(_.toByte).repeat.take(chunkSize.toLong * 2).buffer(bufferSize)

      forAllF { (chunkSize0: Int, bufferSize0: Int) =>
        val chunkSize = (chunkSize0 % 512).abs + 1
        val bufferSize = (bufferSize0 % 511).abs + 1

        val src = source(chunkSize, bufferSize)

        Blocker[IO].use { blocker =>
          src
            .through(text.utf8Decode)
            .foldMonoid
            .flatMap { expected =>
              src
                .through(test(blocker, chunkSize))
                .through(text.utf8Decode)
                .foldMonoid
                .evalMap { actual =>
                  IO(assertEquals(actual, expected))
                }
            }
            .compile
            .drain
        }
      }
    }

    test("can copy more than Int.MaxValue bytes") {
      // Unit test adapted from the original issue reproduction at https://github.com/mrdziuban/fs2-writeOutputStream.

      val byteStream =
        Stream
          .chunk[IO, Byte](Chunk.array(("foobar" * 50000).getBytes(StandardCharsets.UTF_8)))
          .repeatN(7200L) // 6 * 50,000 * 7,200 == 2,160,000,000 > 2,147,483,647 == Int.MaxValue

      def writeToOutputStream(out: OutputStream, blocker: Blocker): IO[Unit] =
        byteStream
          .through(writeOutputStream(IO.pure(out), blocker))
          .compile
          .drain

      Blocker[IO].use { blocker =>
        readOutputStream[IO](blocker, 1024 * 8)(writeToOutputStream(_, blocker))
          .chunkN(6 * 50000)
          .map(c => new String(c.toArray[Byte], StandardCharsets.UTF_8))
          .evalMap(str => IO.pure(str).assertEquals("foobar" * 50000))
          .drain
          .compile
          .drain
      }
    }
  }

  group("unsafeReadInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = unsafeReadInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.map(it => assertEquals(it, bytes.toVector))
        }
      }
    }
  }
}
