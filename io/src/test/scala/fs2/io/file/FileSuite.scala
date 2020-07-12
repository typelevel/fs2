package fs2
package io
package file

import java.nio.file.{Paths, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, IO}
import cats.implicits._
import fs2.io.CollectionCompat._

import scala.concurrent.duration._

class FileSuite extends BaseFileSuite {
  group("readAll") {
    test("retrieves whole content of a file") {
      assert(
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
          .unsafeRunSync() == 4
      )
    }
  }

  group("readRange") {
    test("reads half of a file") {
      assert(
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
          .unsafeRunSync() == 2
      )
    }

    test("reads full file if end is bigger than file size") {
      assert(
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
          .unsafeRunSync() == 4
      )
    }
  }

  group("writeAll") {
    test("simple write") {
      assert(
        Stream
          .resource(Blocker[IO])
          .flatMap { bec =>
            tempFile
              .flatMap(path =>
                Stream("Hello", " world!")
                  .covary[IO]
                  .through(text.utf8Encode)
                  .through(file.writeAll[IO](path, bec))
                  .drain ++ file.readAll[IO](path, bec, 4096).through(text.utf8Decode)
              )
          }
          .compile
          .foldMonoid
          .unsafeRunSync() == "Hello world!"
      )
    }

    test("append") {
      assert(
        Stream
          .resource(Blocker[IO])
          .flatMap { bec =>
            tempFile
              .flatMap { path =>
                val src = Stream("Hello", " world!").covary[IO].through(text.utf8Encode)

                src.through(file.writeAll[IO](path, bec)).drain ++
                  src
                    .through(file.writeAll[IO](path, bec, List(StandardOpenOption.APPEND)))
                    .drain ++
                  file.readAll[IO](path, bec, 4096).through(text.utf8Decode)
              }
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
          .unsafeRunSync() == 4
      )
    }
  }

  group("exists") {
    test("returns false on a non existent file") {
      assert(Blocker[IO].use(b => file.exists[IO](b, Paths.get("nothing"))).unsafeRunSync == false)
    }
    test("returns true on an existing file") {
      assert(
        Blocker[IO]
          .use(b => tempFile.evalMap(file.exists[IO](b, _)).compile.fold(true)(_ && _))
          .unsafeRunSync() == true
      )
    }
  }

  group("permissions") {
    test("should fail for a non existent file") {
      assert(
        Blocker[IO]
          .use(b => file.permissions[IO](b, Paths.get("nothing")))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    test("should return permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      Blocker[IO]
        .use { b =>
          tempFile
            .evalMap(p => file.setPermissions[IO](b, p, permissions) >> file.permissions[IO](b, p))
            .compile
            .lastOrError
        }
        .map(it => assert(it == permissions))
    }
  }

  group("setPermissions") {
    test("should fail for a non existent file") {
      assert(
        Blocker[IO]
          .use(b => file.setPermissions[IO](b, Paths.get("nothing"), Set.empty))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    test("should correctly change file permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      val (initial, updated) = Blocker[IO]
        .use { b =>
          tempFile
            .evalMap { p =>
              for {
                initialPermissions <- file.permissions[IO](b, p)
                _ <- file.setPermissions[IO](b, p, permissions)
                updatedPermissions <- file.permissions[IO](b, p)
              } yield (initialPermissions -> updatedPermissions)
            }
            .compile
            .lastOrError
        }
        .unsafeRunSync()

      assert(initial != updated)
      assert(updated == permissions)
    }
  }

  group("copy") {
    test("returns a path to the new file") {
      assert(
        (for {
          blocker <- Stream.resource(Blocker[IO])
          filePath <- tempFile
          tempDir <- tempDirectory
          result <- Stream.eval(file.copy[IO](blocker, filePath, tempDir.resolve("newfile")))
          exists <- Stream.eval(file.exists[IO](blocker, result))
        } yield exists).compile.fold(true)(_ && _).unsafeRunSync() == true
      )
    }
  }

  group("deleteIfExists") {
    test("should result in non existent file") {
      assert(
        tempFile
          .flatMap(path =>
            Stream
              .resource(Blocker[IO])
              .evalMap(blocker => file.delete[IO](blocker, path) *> file.exists[IO](blocker, path))
          )
          .compile
          .fold(false)(_ || _)
          .unsafeRunSync() == false
      )
    }
  }

  group("delete") {
    test("should fail on a non existent file") {
      assert(
        Blocker[IO]
          .use(blocker => file.delete[IO](blocker, Paths.get("nothing")))
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
        .resource(Blocker[IO])
        .evalMap { b =>
          file.createDirectories[IO](b, testPath.resolve("b/c")) >>
            file.deleteDirectoryRecursively[IO](b, testPath) >>
            file.exists[IO](b, testPath)
        }
        .compile
        .lastOrError
        .map(it => assert(!it))
    }
  }

  group("move") {
    test("should result in the old path being deleted") {
      assert(
        (for {
          blocker <- Stream.resource(Blocker[IO])
          filePath <- tempFile
          tempDir <- tempDirectory
          _ <- Stream.eval(file.move[IO](blocker, filePath, tempDir.resolve("newfile")))
          exists <- Stream.eval(file.exists[IO](blocker, filePath))
        } yield exists).compile.fold(false)(_ || _).unsafeRunSync() == false
      )
    }
  }

  group("size") {
    test("should return correct size of ay file") {
      assert(
        tempFile
          .flatTap(modify)
          .flatMap(path =>
            Stream
              .resource(Blocker[IO])
              .evalMap(blocker => file.size[IO](blocker, path))
          )
          .compile
          .lastOrError
          .unsafeRunSync() == 4L
      )
    }
  }

  group("tempFileStream") {
    test("should remove the file following stream closure") {
      Blocker[IO]
        .use { b =>
          file
            .tempFileStream[IO](b, Paths.get(""))
            .evalMap(path => file.exists[IO](b, path).map(_ -> path))
            .compile
            .lastOrError
            .flatMap {
              case (existsBefore, path) => file.exists[IO](b, path).map(existsBefore -> _)
            }
        }
        .map(it => assert(it == true -> false))
    }

    test("should not fail if the file is deleted before the stream completes") {
      Stream
        .resource(Blocker[IO])
        .flatMap { b =>
          file
            .tempFileStream[IO](b, Paths.get(""))
            .evalMap(path => file.delete[IO](b, path))
        }
        .compile
        .lastOrError
        .attempt
        .map(it => assert(it.isRight))
    }
  }

  group("tempDirectoryStream") {
    test("should remove the directory following stream closure") {
      Blocker[IO]
        .use { b =>
          file
            .tempDirectoryStream[IO](b, Paths.get(""))
            .evalMap(path => file.exists[IO](b, path).map(_ -> path))
            .compile
            .lastOrError
            .flatMap {
              case (existsBefore, path) => file.exists[IO](b, path).map(existsBefore -> _)
            }
        }
        .map(it => assert(it == true -> false))
    }

    test("should not fail if the directory is deleted before the stream completes") {
      Stream
        .resource(Blocker[IO])
        .flatMap { b =>
          file
            .tempDirectoryStream[IO](b, Paths.get(""))
            .evalMap(path => file.delete[IO](b, path))
        }
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
          .flatMap(path =>
            Stream
              .resource(Blocker[IO])
              .evalMap(blocker =>
                file
                  .createDirectory[IO](blocker, path.resolve("temp"))
                  .bracket(file.exists[IO](blocker, _))(file.deleteIfExists[IO](blocker, _).void)
              )
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
          .flatMap(path =>
            Stream
              .resource(Blocker[IO])
              .evalMap(blocker =>
                file
                  .createDirectories[IO](blocker, path.resolve("temp/inner"))
                  .bracket(file.exists[IO](blocker, _))(file.deleteIfExists[IO](blocker, _).void)
              )
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
        Stream
          .resource(Blocker[IO])
          .flatMap { blocker =>
            tempDirectory
              .flatMap(path => file.directoryStream[IO](blocker, path))
          }
          .compile
          .toList
          .unsafeRunSync()
          .length == 0
      )
    }

    test("returns all files in a directory correctly") {
      assert(
        Stream
          .resource(Blocker[IO])
          .flatMap { blocker =>
            tempFiles(10)
              .flatMap { paths =>
                val parent = paths.head.getParent
                file.directoryStream[IO](blocker, parent).tupleRight(paths)
              }
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
        Stream
          .resource(Blocker[IO])
          .flatMap { blocker =>
            tempFile
              .flatMap { path =>
                file.walk[IO](blocker, path.getParent).map(_.normalize == path.normalize)
              }
          }
          .compile
          .toList
          .unsafeRunSync()
          .length == 2 // the directory and the file
      )
    }

    test("returns all files in a directory correctly") {
      assert(
        Stream
          .resource(Blocker[IO])
          .flatMap { blocker =>
            tempFiles(10)
              .flatMap { paths =>
                val parent = paths.head.getParent
                file.walk[IO](blocker, parent).tupleRight(parent :: paths)
              }
          }
          .map { case (path, paths) => paths.exists(_.normalize == path.normalize) }
          .compile
          .fold(true)(_ & _)
          .unsafeRunSync() == true // the directory itself and the files
      )
    }

    test("returns all files in a nested tree correctly") {
      assert(
        Stream
          .resource(Blocker[IO])
          .flatMap { blocker =>
            tempFilesHierarchy
              .flatMap(topDir => file.walk[IO](blocker, topDir))
          }
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
    Stream
      .resource(Blocker[IO])
      .flatMap { bec =>
        tempDirectory.flatMap { dir =>
          Stream.eval(Ref.of[IO, Int](0)).flatMap { counter =>
            val path = counter.modify(i => (i + 1, i)).map(i => dir.resolve(i.toString))
            val write = Stream(0x42.toByte).repeat
              .buffer(bufferSize)
              .take(totalBytes)
              .through(file.writeRotate[IO](path, rotateLimit, bec))
              .compile
              .drain
            val verify = file
              .directoryStream[IO](bec, dir)
              .compile
              .toList
              .flatMap { paths =>
                paths
                  .sortBy(_.toString)
                  .traverse(p => file.size[IO](bec, p))
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
      }
      .compile
      .lastOrError
  }
}
