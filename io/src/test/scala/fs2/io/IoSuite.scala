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

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.util.concurrent.Executors
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Fs2Suite
import fs2.Err
import scala.concurrent.ExecutionContext
import org.scalacheck.effect.PropF.forAllF

class IoSuite extends Fs2Suite {
  group("readInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = readInputStream(IO(is), chunkSize)
        stream.compile.toVector.assertEquals(bytes.toVector)
      }
    }

    test("buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = readInputStream(IO(is), chunkSize)
        stream
          .buffer(chunkSize * 2)
          .compile
          .toVector
          .assertEquals(bytes.toVector)
      }
    }
  }

  group("readOutputStream") {
    test("writes data and terminates when `f` returns") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        readOutputStream[IO](chunkSize)((os: OutputStream) =>
          IO.blocking[Unit](os.write(bytes))
        ).compile
          .toVector
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
        readOutputStream[IO](chunkSize)((_: OutputStream) =>
          IO.raiseError(new Err)
        ).compile.drain.intercept[Err].void
      }
    }

    test("Doesn't deadlock with size-1 ContextShift thread pool") {
      val ioRuntime: IORuntime = {
        val compute = {
          val pool = Executors.newFixedThreadPool(1)
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
          }
        )
      }
      def write(os: OutputStream): IO[Unit] =
        IO {
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

      prog
        .unsafeToFuture()(ioRuntime) // run explicitly so we can override the runtime
    }
  }

  group("unsafeReadInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = unsafeReadInputStream(IO(is), chunkSize)
        stream.compile.toVector.assertEquals(bytes.toVector)
      }
    }
  }
}
