package fs2.io

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.util.concurrent.Executors
import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import fs2.Fs2Spec
import scala.concurrent.ExecutionContext

class IoSpec extends Fs2Spec {
  "readInputStream" - {
    "non-buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.asserting(it => assert(it.toArray === bytes))
        }
    }

    "buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.buffer(chunkSize * 2).compile.toVector.asserting(it => assert(it.toArray === bytes))
        }
    }
  }

  "readOutputStream" - {
    "writes data and terminates when `f` returns" in forAll(
      arrayGenerator[Byte],
      intsBetween(1, 20)
    ) { (bytes: Array[Byte], chunkSize: Int) =>
      Blocker[IO].use { blocker =>
        readOutputStream[IO](blocker, chunkSize)((os: OutputStream) =>
          blocker.delay[IO, Unit](os.write(bytes))
        ).compile
          .to(Array)
          .asserting(it => assert(it === bytes))
      }
    }

    "can be manually closed from inside `f`" in forAll(intsBetween(1, 20)) { chunkSize: Int =>
      Blocker[IO].use { blocker =>
        readOutputStream[IO](blocker, chunkSize)((os: OutputStream) => IO(os.close()) *> IO.never).compile.toVector
          .asserting(it => assert(it == Vector.empty))
      }
    }

    "fails when `f` fails" in forAll(intsBetween(1, 20)) { chunkSize: Int =>
      val e = new Exception("boom")
      Blocker[IO].use { blocker =>
        readOutputStream[IO](blocker, chunkSize)((_: OutputStream) => IO.raiseError(e)).compile.toVector.attempt
          .asserting(it => assert(it == Left(e)))
      }
    }

    "Doesn't deadlock with size-1 ContextShift thread pool" in {
      val pool = Resource
        .make(IO(Executors.newFixedThreadPool(1)))(ec => IO(ec.shutdown()))
        .map(ExecutionContext.fromExecutor)
        .map(IO.contextShift)
      def write(os: OutputStream): IO[Unit] = IO {
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
          .asserting(it => assert(it.size == 5))
      }
    }
  }

  "unsafeReadInputStream" - {
    "non-buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = unsafeReadInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.asserting(it => assert(it.toArray === bytes))
        }
    }
  }
}
