package fs2
package io
package file

import java.nio.file.StandardOpenOption

import cats.effect.{Blocker, IO}
import cats.implicits._

import scala.concurrent.duration._

class FileSpec extends BaseFileSpec {

  "readAll" - {
    "retrieves whole content of a file" in {
      Stream
        .resource(Blocker[IO])
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
        .resource(Blocker[IO])
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
        .resource(Blocker[IO])
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
        .resource(Blocker[IO])
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
        .resource(Blocker[IO])
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

  "tail" - {
    "keeps reading a file as it is appended" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempFile
            .flatMap { path =>
              file
                .tail[IO](path, blocker, 4096, pollDelay = 25.millis)
                .concurrently(modifyLater(path, blocker))
            }
        }
        .take(4)
        .compile
        .toList
        .map(_.size)
        .unsafeRunSync() shouldBe 4
    }
  }

  "streamDirectory" - {
    "returns an empty Stream on an empty directory" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempDirectory
            .flatMap { path =>
              file.streamDirectory[IO](blocker, path)
            }
        }
        .compile
        .toList
        .unsafeRunSync()
        .length shouldBe 0
    }

    "returns all files in a directory correctly" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempFiles(10)
            .flatMap { paths =>
              val parent = paths.head.getParent
              file.streamDirectory[IO](blocker, parent).tupleRight(paths)
            }
        }
        .map { case (path, paths) => paths.exists(_.normalize === path.normalize) }
        .compile
        .fold(true)(_ & _)
        .unsafeRunSync() shouldBe true
    }
  }

  "walk" - {
    "returns the only file in a directory correctly" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempFile
            .flatMap { path =>
              file.walk[IO](blocker, path.getParent).map(_.normalize === path.normalize)
            }
        }
        .compile
        .toList
        .unsafeRunSync()
        .length shouldBe 2 // the directory and the file
    }

    "returns all files in a directory correctly" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempFiles(10)
            .flatMap { paths =>
              val parent = paths.head.getParent
              file.walk[IO](blocker, parent).tupleRight(parent :: paths)
            }
        }
        .map { case (path, paths) => paths.exists(_.normalize === path.normalize) }
        .compile
        .fold(true)(_ & _)
        .unsafeRunSync() shouldBe true // the directory itself and the files
    }

    "returns all files in a nested tree correctly" in {
      Stream
        .resource(Blocker[IO])
        .flatMap { blocker =>
          tempFilesHierarchy
            .flatMap { topDir =>
              file.walk[IO](blocker, topDir)
            }
        }
        .compile
        .toList
        .unsafeRunSync()
        .length shouldBe 31 // the root + 5 children + 5 files per child directory
    }
  }

}
