package fs2.io

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.util.concurrent.Executors
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Fs2Suite
import scala.concurrent.ExecutionContext
import org.scalacheck.effect.PropF.forAllF

class IoSuite extends Fs2Suite {
  group("readInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = readInputStream(IO(is), chunkSize)
        stream.compile.toVector.map(it => assertEquals(it, bytes.toVector))
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
          .map(it => assertEquals(it, bytes.toVector))
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
          .to(Vector)
          .map(it => assertEquals(it, bytes.toVector))
      }
    }

    test("can be manually closed from inside `f`") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        readOutputStream[IO](chunkSize)((os: OutputStream) =>
          IO(os.close()) *> IO.never
        ).compile.toVector
          .map(it => assert(it == Vector.empty))
      }
    }

    test("fails when `f` fails") {
      forAllF { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val e = new Exception("boom")
        readOutputStream[IO](chunkSize)((_: OutputStream) =>
          IO.raiseError(e)
        ).compile.toVector.attempt
          .map(it => assert(it == Left(e)))
      }
    }

    test("Doesn't deadlock with size-1 ContextShift thread pool") {
      implicit val ioRuntime: IORuntime = {
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
      readOutputStream[IO](chunkSize = 1)(write)
        .take(5)
        .compile
        .toVector
        .map(it => assert(it.size == 5))
        .unsafeRunSync()
    }
  }

  group("unsafeReadInputStream") {
    test("non-buffered") {
      forAllF { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        val stream = unsafeReadInputStream(IO(is), chunkSize)
        stream.compile.toVector.map(it => assertEquals(it, bytes.toVector))
      }
    }
  }
}
