package fs2.io

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.util.concurrent.Executors
import cats.effect.{Blocker, ContextShift, IO, Resource}
import fs2.Fs2Suite
import scala.concurrent.ExecutionContext

class IoSuite extends Fs2Suite {
  group("readInputStream") {
    test("non-buffered") {
      forAllAsync { (bytes: Array[Byte], chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.map(it => assertEquals(it, bytes.toVector))
        }
      }
    }

    test("buffered") {
      forAllAsync { (bytes: Array[Byte], chunkSize0: Int) =>
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
      forAllAsync { (bytes: Array[Byte], chunkSize0: Int) =>
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
      forAllAsync { (chunkSize0: Int) =>
        val chunkSize = (chunkSize0 % 20).abs + 1
        Blocker[IO].use { blocker =>
          readOutputStream[IO](blocker, chunkSize)((os: OutputStream) =>
            IO(os.close()) *> IO.never
          ).compile.toVector
            .map(it => assert(it == Vector.empty))
        }
      }
    }

    test("fails when `f` fails") {
      forAllAsync { (chunkSize0: Int) =>
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
      val pool = Resource
        .make(IO(Executors.newFixedThreadPool(1)))(ec => IO(ec.shutdown()))
        .map(ExecutionContext.fromExecutor)
        .map(IO.contextShift)
      def write(os: OutputStream): IO[Unit] =
        IO {
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
          os.write(1)
        }
      Blocker[IO].use { blocker =>
        // Note: name `contextShiftIO` is important because it shadows the outer implicit, preventing ambiguity
        pool
          .use { implicit contextShiftIO: ContextShift[IO] =>
            readOutputStream[IO](blocker, chunkSize = 1)(write)
              .take(5)
              .compile
              .toVector
          }
          .map(it => assert(it.size == 5))
      }
    }
  }

  group("unsafeReadInputStream") {
    test("non-buffered") {
      forAllAsync { (bytes: Array[Byte], chunkSize0: Int) =>
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
