package fs2
package io
package file

import java.nio.file.StandardOpenOption

import cats.effect.IO
import cats.implicits._

class FileSpec extends BaseFileSpec {

  "readAll" - {
    "retrieves whole content of a file" in {
      Stream
        .resource(blockingExecutionContext)
        .flatMap { bec =>
          tempFile
            .flatTap(modify)
            .flatMap(path => file.readAll[IO](path, bec, 4096))
        }
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 4
    }
  }

  "readRange" - {
    "reads half of a file" in {
      Stream
        .resource(blockingExecutionContext)
        .flatMap { bec =>
          tempFile
            .flatTap(modify)
            .flatMap(path => file.readRange[IO](path, bec, 4096, 0, 2))
        }
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 2
    }

    "reads full file if end is bigger than file size" in {
      Stream
        .resource(blockingExecutionContext)
        .flatMap { bec =>
          tempFile
            .flatTap(modify)
            .flatMap(path => file.readRange[IO](path, bec, 4096, 0, 100))
        }
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 4
    }
  }

  "writeAll" - {
    "simple write" in {
      Stream
        .resource(blockingExecutionContext)
        .flatMap { bec =>
          tempFile
            .flatMap(
              path =>
                Stream("Hello", " world!")
                  .covary[IO]
                  .through(text.utf8Encode)
                  .through(file.writeAll[IO](path, bec))
                  .drain ++ file.readAll[IO](path, bec, 4096).through(text.utf8Decode))
        }
        .compile
        .foldMonoid
        .unsafeRunSync() shouldBe "Hello world!"
    }

    "append" in {
      Stream
        .resource(blockingExecutionContext)
        .flatMap { bec =>
          tempFile
            .flatMap { path =>
              val src = Stream("Hello", " world!").covary[IO].through(text.utf8Encode)

              src.through(file.writeAll[IO](path, bec)).drain ++
                src.through(file.writeAll[IO](path, bec, List(StandardOpenOption.APPEND))).drain ++
                file.readAll[IO](path, bec, 4096).through(text.utf8Decode)
            }
        }
        .compile
        .foldMonoid
        .unsafeRunSync() shouldBe "Hello world!Hello world!"
    }
  }
}
