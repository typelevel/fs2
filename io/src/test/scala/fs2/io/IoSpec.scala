package fs2.io

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}

import cats.effect.{IO, Sync}
import fs2.{Fs2Spec, hash, io}

class IoSpec extends Fs2Spec {
  "readInputStream" - {
    "arbitrary.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStream(IO(is), chunkSize.get)
      val example = stream.runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }

    "arbitrary.buffer.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStream(IO(is), chunkSize.get)
      val example = stream.buffer(chunkSize.get * 2).runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "readInputStreamAsync" - {
    "arbitrary.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStreamAsync(IO(is), chunkSize.get)
      val example = stream.runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }

    "arbitrary.buffer.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStreamAsync(IO(is), chunkSize.get)
      val example = stream.buffer(chunkSize.get * 2).runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "unsafeReadInputStream" - {
    "arbitrary.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = unsafeReadInputStream(IO(is), chunkSize.get)
      val example = stream.runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "unsafeReadInputStreamAsync" - {
    "arbitrary.runLog" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = unsafeReadInputStreamAsync(IO(is), chunkSize.get)
      val example = stream.runLog.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "unsafeReadAllSync" - {
    "readAll and hash" in {

      def checksum[F[_]](f: Path)(implicit F: Sync[F]): F[Vector[Byte]] =
        io.file.readAll[F](f, 16 * 1024)
          .through(hash.md5)
          .runLog

      val data : Array[Byte] = (0 to 111).map(_.asInstanceOf[Byte]).toArray
      val tmpFile = Files.createTempFile("readAll", "hash")
      Files.write(tmpFile, data)

      val result = checksum[IO](tmpFile).unsafeRunSync()
      val str = result.map("%02x" format _).mkString

      str shouldBe "d1fec2ac3715e791ca5f489f300381b3"
    }
  }
}
