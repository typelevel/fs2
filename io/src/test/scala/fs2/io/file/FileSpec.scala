package fs2
package io
package file

import scala.concurrent.ExecutionContext.global

import cats.effect.IO
import cats.implicits._

class FileSpec extends BaseFileSpec {

  "readAll" - {
    "retrieves whole content of a file" in {
      tempFile
        .flatTap(modify)
        .flatMap(path => file.readAll[IO](path, global, 4096))
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 4
    }
  }

  "readRange" - {
    "reads half of a file" in {
      tempFile
        .flatTap(modify)
        .flatMap(path => file.readRange[IO](path, global, 4096, 0, 2))
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 2
    }

    "reads full file if end is bigger than file size" in {
      tempFile
        .flatTap(modify)
        .flatMap(path => file.readRange[IO](path, global, 4096, 0, 100))
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 4
    }
  }
}
