package fs2
package io
package file

import java.nio.file.{Paths, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.concurrent.Ref
import cats.effect.IO
import cats.implicits._
import fs2.io.CollectionCompat._

import scala.concurrent.duration._

class FileSuite extends BaseFileSuite {
  group("readAll") {
    test("retrieves whole content of a file") {
      assert(
        tempFile
          .flatTap(modify)
          .flatMap(path => file.readAll[IO](path, 4096))
          .compile
          .toList
          .map(_.size)
          .unsafeRunSync() == 4
      )
    }
  }

  group("readRange") {
    test("reads half of a file") {
      assert(
        tempFile
          .flatTap(modify)
          .flatMap(path => file.readRange[IO](path, 4096, 0, 2))
          .compile
          .toList
          .map(_.size)
          .unsafeRunSync() == 2
      )
    }

    test("reads full file if end is bigger than file size") {
      assert(
        tempFile
          .flatTap(modify)
          .flatMap(path => file.readRange[IO](path, 4096, 0, 100))
          .compile
          .toList
          .map(_.size)
          .unsafeRunSync() == 4
      )
    }
  }

  group("writeAll") {
    test("simple write") {
      assert(
        tempFile
          .flatMap(path =>
            Stream("Hello", " world!")
              .covary[IO]
              .through(text.utf8Encode)
              .through(file.writeAll[IO](path))
              .drain ++ file.readAll[IO](path, 4096).through(text.utf8Decode)
          )
          .compile
          .foldMonoid
          .unsafeRunSync() == "Hello world!"
      )
    }

    test("append") {
      assert(
        tempFile
          .flatMap { path =>
            val src = Stream("Hello", " world!").covary[IO].through(text.utf8Encode)

            src.through(file.writeAll[IO](path)).drain ++
              src
                .through(file.writeAll[IO](path, List(StandardOpenOption.APPEND)))
                .drain ++
              file.readAll[IO](path, 4096).through(text.utf8Decode)
          }
          .compile
          .foldMonoid
          .unsafeRunSync() == "Hello world!Hello world!"
      )
    }
  }

  group("tail") {
    test("keeps reading a file as it is appended") {
      assert(
        tempFile
          .flatMap { path =>
            file
              .tail[IO](path, 4096, pollDelay = 25.millis)
              .concurrently(modifyLater(path))
          }
          .take(4)
          .compile
          .toList
          .map(_.size)
          .unsafeRunSync() == 4
      )
    }
  }

  group("exists") {
    test("returns false on a non existent file") {
      assert(file.exists[IO](Paths.get("nothing")).unsafeRunSync == false)
    }
    test("returns true on an existing file") {
      assert(
        tempFile
          .evalMap(file.exists[IO](_))
          .compile
          .fold(true)(_ && _)
          .unsafeRunSync() == true
      )
    }
  }

  group("permissions") {
    test("should fail for a non existent file") {
      assert(
        file
          .permissions[IO](Paths.get("nothing"))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    test("should return permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      tempFile
        .evalMap(p => file.setPermissions[IO](p, permissions) >> file.permissions[IO](p))
        .compile
        .lastOrError
        .map(it => assert(it == permissions))
    }
  }

  group("setPermissions") {
    test("should fail for a non existent file") {
      assert(
        file
          .setPermissions[IO](Paths.get("nothing"), Set.empty)
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    test("should correctly change file permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      val (initial, updated) =
        tempFile
          .evalMap { p =>
            for {
              initialPermissions <- file.permissions[IO](p)
              _ <- file.setPermissions[IO](p, permissions)
              updatedPermissions <- file.permissions[IO](p)
            } yield (initialPermissions -> updatedPermissions)
          }
          .compile
          .lastOrError
          .unsafeRunSync()

      assert(initial != updated)
      assert(updated == permissions)
    }
  }

  group("copy") {
    test("returns a path to the new file") {
      assert(
        (for {
          filePath <- tempFile
          tempDir <- tempDirectory
          result <- Stream.eval(file.copy[IO](filePath, tempDir.resolve("newfile")))
          exists <- Stream.eval(file.exists[IO](result))
        } yield exists).compile.fold(true)(_ && _).unsafeRunSync() == true
      )
    }
  }

  group("deleteIfExists") {
    test("should result in non existent file") {
      assert(
        tempFile
          .flatMap(path => Stream.eval(file.delete[IO](path) *> file.exists[IO](path)))
          .compile
          .fold(false)(_ || _)
          .unsafeRunSync() == false
      )
    }
  }

  group("delete") {
    test("should fail on a non existent file") {
      assert(
        file
          .delete[IO](Paths.get("nothing"))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
  }

  group("deleteDirectoryRecursively") {
    test("should remove a non-empty directory") {
      val testPath = Paths.get("a")
      Stream
        .eval(
          file.createDirectories[IO](testPath.resolve("b/c")) >>
            file.deleteDirectoryRecursively[IO](testPath) >>
            file.exists[IO](testPath)
        )
        .compile
        .lastOrError
        .map(it => assert(!it))
    }
  }

  group("move") {
    test("should result in the old path being deleted") {
      assert(
        (for {
          filePath <- tempFile
          tempDir <- tempDirectory
          _ <- Stream.eval(file.move[IO](filePath, tempDir.resolve("newfile")))
          exists <- Stream.eval(file.exists[IO](filePath))
        } yield exists).compile.fold(false)(_ || _).unsafeRunSync() == false
      )
    }
  }

  group("size") {
    test("should return correct size of ay file") {
      assert(
        tempFile
          .flatTap(modify)
          .flatMap(path => Stream.eval(file.size[IO](path)))
          .compile
          .lastOrError
          .unsafeRunSync() == 4L
      )
    }
  }

  group("tempFileStream") {
    test("should remove the file following stream closure") {
      file
        .tempFileStream[IO](Paths.get(""))
        .evalMap(path => file.exists[IO](path).map(_ -> path))
        .compile
        .lastOrError
        .flatMap {
          case (existsBefore, path) => file.exists[IO](path).map(existsBefore -> _)
        }
        .map(it => assert(it == true -> false))
    }

    test("should not fail if the file is deleted before the stream completes") {
      file
        .tempFileStream[IO](Paths.get(""))
        .evalMap(path => file.delete[IO](path))
        .compile
        .lastOrError
        .attempt
        .map(it => assert(it.isRight))
    }
  }

  group("tempDirectoryStream") {
    test("should remove the directory following stream closure") {
      file
        .tempDirectoryStream[IO](Paths.get(""))
        .evalMap(path => file.exists[IO](path).map(_ -> path))
        .compile
        .lastOrError
        .flatMap {
          case (existsBefore, path) => file.exists[IO](path).map(existsBefore -> _)
        }
        .map(it => assert(it == true -> false))
    }

    test("should not fail if the directory is deleted before the stream completes") {
      file
        .tempDirectoryStream[IO](Paths.get(""))
        .evalMap(path => file.delete[IO](path))
        .compile
        .lastOrError
        .attempt
        .map(it => assert(it.isRight))
    }
  }

  group("createDirectory") {
    test("should return in an existing path") {
      assert(
        tempDirectory
          .evalMap(path =>
            file
              .createDirectory[IO](path.resolve("temp"))
              .bracket(file.exists[IO](_))(file.deleteIfExists[IO](_).void)
          )
          .compile
          .fold(true)(_ && _)
          .unsafeRunSync() == true
      )
    }
  }

  group("createDirectories") {
    test("should return in an existing path") {
      assert(
        tempDirectory
          .evalMap(path =>
            file
              .createDirectories[IO](path.resolve("temp/inner"))
              .bracket(file.exists[IO](_))(file.deleteIfExists[IO](_).void)
          )
          .compile
          .fold(true)(_ && _)
          .unsafeRunSync() == true
      )
    }
  }

  group("directoryStream") {
    test("returns an empty Stream on an empty directory") {
      assert(
        tempDirectory
          .flatMap(path => file.directoryStream[IO](path))
          .compile
          .toList
          .unsafeRunSync()
          .length == 0
      )
    }

    test("returns all files in a directory correctly") {
      assert(
        tempFiles(10)
          .flatMap { paths =>
            val parent = paths.head.getParent
            file.directoryStream[IO](parent).tupleRight(paths)
          }
          .map { case (path, paths) => paths.exists(_.normalize == path.normalize) }
          .compile
          .fold(true)(_ & _)
          .unsafeRunSync() == true
      )
    }
  }

  group("walk") {
    test("returns the only file in a directory correctly") {
      assert(
        tempFile
          .flatMap { path =>
            file.walk[IO](path.getParent).map(_.normalize == path.normalize)
          }
          .compile
          .toList
          .unsafeRunSync()
          .length == 2 // the directory and the file
      )
    }

    test("returns all files in a directory correctly") {
      assert(
        tempFiles(10)
          .flatMap { paths =>
            val parent = paths.head.getParent
            file.walk[IO](parent).tupleRight(parent :: paths)
          }
          .map { case (path, paths) => paths.exists(_.normalize == path.normalize) }
          .compile
          .fold(true)(_ & _)
          .unsafeRunSync() == true // the directory itself and the files
      )
    }

    test("returns all files in a nested tree correctly") {
      assert(
        tempFilesHierarchy
          .flatMap(topDir => file.walk[IO](topDir))
          .compile
          .toList
          .unsafeRunSync()
          .length == 31 // the root + 5 children + 5 files per child directory
      )
    }
  }

  test("writeRotate") {
    val bufferSize = 100
    val totalBytes = 1000
    val rotateLimit = 150
    tempDirectory
      .flatMap { dir =>
        Stream.eval(Ref.of[IO, Int](0)).flatMap { counter =>
          val path = counter.modify(i => (i + 1, i)).map(i => dir.resolve(i.toString))
          val write = Stream(0x42.toByte).repeat
            .buffer(bufferSize)
            .take(totalBytes)
            .through(file.writeRotate[IO](path, rotateLimit))
            .compile
            .drain
          val verify = file
            .directoryStream[IO](dir)
            .compile
            .toList
            .flatMap { paths =>
              paths
                .sortBy(_.toString)
                .traverse(p => file.size[IO](p))
            }
            .map { sizes =>
              assert(sizes.size == ((totalBytes + rotateLimit - 1) / rotateLimit))
              assert(
                sizes.init.forall(_ == rotateLimit) && sizes.last == (totalBytes % rotateLimit)
              )
            }
          Stream.eval(write *> verify)
        }
      }
      .compile
      .lastOrError
  }
}
