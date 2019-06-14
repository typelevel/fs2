package fs2.io

import java.io.{ByteArrayInputStream, InputStream}
import cats.effect.{Blocker, IO}
import fs2.Fs2Spec

class IoSpec extends Fs2Spec {
  "readInputStream" - {
    "non-buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.asserting(_.toArray shouldBe bytes)
        }
    }

    "buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = readInputStream(IO(is), chunkSize, blocker)
          stream.buffer(chunkSize * 2).compile.toVector.asserting(_.toArray shouldBe bytes)
        }
    }
  }

  "unsafeReadInputStream" - {
    "non-buffered" in forAll(arrayGenerator[Byte], intsBetween(1, 20)) {
      (bytes: Array[Byte], chunkSize: Int) =>
        val is: InputStream = new ByteArrayInputStream(bytes)
        Blocker[IO].use { blocker =>
          val stream = unsafeReadInputStream(IO(is), chunkSize, blocker)
          stream.compile.toVector.asserting(_.toArray shouldBe bytes)
        }
    }
  }
}
