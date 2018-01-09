package fs2.io

import java.io.{ByteArrayInputStream, InputStream}
import cats.effect.IO
import fs2.Fs2Spec
import fs2.TestUtil._

class IoSpec extends Fs2Spec {
  "readInputStream" - {
    "non-buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStream(IO(is), chunkSize.get)
      val example = stream.compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }

    "buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStream(IO(is), chunkSize.get)
      val example =
        stream.buffer(chunkSize.get * 2).compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "readInputStreamAsync" - {
    "non-buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStreamAsync(IO(is), chunkSize.get)
      val example = stream.compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }

    "buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = readInputStreamAsync(IO(is), chunkSize.get)
      val example =
        stream.buffer(chunkSize.get * 2).compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "unsafeReadInputStream" - {
    "non-buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = unsafeReadInputStream(IO(is), chunkSize.get)
      val example = stream.compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

  "unsafeReadInputStreamAsync" - {
    "non-buffered" in forAll { (bytes: Array[Byte], chunkSize: SmallPositive) =>
      val is: InputStream = new ByteArrayInputStream(bytes)
      val stream = unsafeReadInputStreamAsync(IO(is), chunkSize.get)
      val example = stream.compile.toVector.unsafeRunSync.toArray
      example shouldBe bytes
    }
  }

}
