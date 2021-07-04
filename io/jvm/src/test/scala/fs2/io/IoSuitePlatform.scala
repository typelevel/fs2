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

import java.io.OutputStream
import java.util.concurrent.Executors
import cats.effect.{IO, Resource}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import fs2.Fs2Suite
import fs2.Err
import scala.concurrent.ExecutionContext
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.effect.PropF.forAllF

class IoSuitePlatform extends Fs2Suite {

  group("readOutputStream") {
    test("writes data and terminates when `f` returns") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        readOutputStream[IO](chunkSize)((os: OutputStream) =>
          IO.blocking[Unit](os.write(bytes))
        ).compile.toVector
          .assertEquals(bytes.toVector)
      }
    }

    test("can be manually closed from inside `f`") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        readOutputStream[IO](chunkSize)((os: OutputStream) =>
          IO(os.close()) *> IO.never
        ).compile.toVector
          .assertEquals(Vector.empty)
      }
    }

    test("fails when `f` fails") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        readOutputStream[IO](chunkSize)((_: OutputStream) => IO.raiseError(new Err)).compile.drain
          .intercept[Err]
          .void
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

        readOutputStream[IO](chunkSize.value) { (os: OutputStream) =>
          IO.delay(os.write(bytes))
        }.chunks.head.compile.lastOrError
          .map(chunk => assertEquals(chunk.size, chunkSize.value))
      }
    }

    test("PipedInput/OutputStream used to track threads, fs2 reimplementation works") {
      readOutputStream(1024) { os =>
        IO.blocking {
          val t = new Thread(() => os.write(123))
          t.start
          t.join
          Thread.sleep(100L)
        }
      }.compile.drain.map(_ => assert(true))
    }

    test("different chunk sizes function correctly") {

      def test(chunkSize: Int): Pipe[IO, Byte, Byte] = source => {
        readOutputStream(chunkSize) { os =>
          source.through(writeOutputStream(IO.delay(os), true)).compile.drain
        }
      }

      def source(chunkSize: Int, bufferSize: Int): Stream[Pure, Byte] =
        Stream.range(65, 75).map(_.toByte).repeat.take(chunkSize.toLong * 2).buffer(bufferSize)

      forAllF { (chunkSize0: Int, bufferSize0: Int) =>
        val chunkSize = (chunkSize0 % 512).abs + 1
        val bufferSize = (bufferSize0 % 511).abs + 1

        val src = source(chunkSize, bufferSize)

        src
          .through(text.utf8Decode)
          .foldMonoid
          .flatMap { expected =>
            src
              .through(test(chunkSize))
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

  test("Doesn't deadlock with size-1 thread pool") {
    def singleThreadedRuntime(): IORuntime = {
      val compute = {
        val pool = Executors.newSingleThreadExecutor()
        (ExecutionContext.fromExecutor(pool), () => pool.shutdown())
      }
      val blocking = IORuntime.createDefaultBlockingExecutionContext()
      val scheduler = IORuntime.createDefaultScheduler()
      IORuntime(
        compute._1,
        blocking._1,
        scheduler._1,
        () => {
          compute._2.apply()
          blocking._2.apply()
          scheduler._2.apply()
        },
        IORuntimeConfig()
      )
    }

    val runtime = Resource.make(IO(singleThreadedRuntime()))(rt => IO(rt.shutdown()))

    def write(os: OutputStream): IO[Unit] =
      IO.blocking {
        os.write(1)
        os.write(1)
        os.write(1)
        os.write(1)
        os.write(1)
        os.write(1)
      }

    val prog = readOutputStream[IO](chunkSize = 1)(write)
      .take(5)
      .compile
      .toVector
      .map(_.size)
      .assertEquals(5)

    runtime.use { rt =>
      IO.fromFuture(IO(prog.unsafeToFuture()(rt)))
    }
  }

}
