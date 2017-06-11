package fs2.io

import java.io.{ByteArrayInputStream, InputStream}
import cats.effect.IO
import fs2.Fs2Spec

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
}
