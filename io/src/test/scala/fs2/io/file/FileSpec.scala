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

class FileSpec extends BaseFileSpec {
  "readAll" - {
    "retrieves whole content of a file" in {
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

  "readRange" - {
    "reads half of a file" in {
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

    "reads full file if end is bigger than file size" in {
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

  "writeAll" - {
    "simple write" in {
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

    "append" in {
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

  "tail" - {
    "keeps reading a file as it is appended" in {
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

  "exists" - {
    "returns false on a non existent file" in {
      assert(Blocker[IO].use(b => file.exists[IO](b, Paths.get("nothing"))).unsafeRunSync == false)
    }
    "returns true on an existing file" in {
      assert(
        Blocker[IO]
          .use(b => tempFile.evalMap(file.exists[IO](b, _)).compile.fold(true)(_ && _))
          .unsafeRunSync() == true
      )
    }
  }

  "permissions" - {
    "should fail for a non existent file" in {
      assert(
        Blocker[IO]
          .use(b => file.permissions[IO](b, Paths.get("nothing")))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    "should return permissions for existing file" in {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      Blocker[IO]
        .use { b =>
          tempFile
            .evalMap(p => file.setPermissions[IO](b, p, permissions) >> file.permissions[IO](b, p))
            .compile
            .lastOrError
        }
        .asserting(it => assert(it == permissions))
    }
  }

  "setPermissions" - {
    "should fail for a non existent file" in {
      assert(
        Blocker[IO]
          .use(b => file.setPermissions[IO](b, Paths.get("nothing"), Set.empty))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
    "should correctly change file permissions for existing file" in {
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

  "copy" - {
    "returns a path to the new file" in {
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

  "deleteIfExists" - {
    "should result in non existent file" in {
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

  "delete" - {
    "should fail on a non existent file" in {
      assert(
        Blocker[IO]
          .use(blocker => file.delete[IO](blocker, Paths.get("nothing")))
          .attempt
          .unsafeRunSync()
          .isLeft == true
      )
    }
  }

  "deleteDirectoryRecursively" - {
    "should remove a non-empty directory" in {
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
        .asserting(it => assert(!it))
    }
  }

  "move" - {
    "should result in the old path being deleted" in {
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

  "size" - {
    "should return correct size of ay file" in {
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

  "tempFileStream" - {
    "should remove the file following stream closure" in {
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
        .asserting(it => assert(it == true -> false))
    }

    "should not fail if the file is deleted before the stream completes" in {
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
        .asserting(it => assert(it.isRight))
    }
  }

  "tempDirectoryStream" - {
    "should remove the directory following stream closure" in {
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
        .asserting(it => assert(it == true -> false))
    }

    "should not fail if the directory is deleted before the stream completes" in {
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
        .asserting(it => assert(it.isRight))
    }
  }

  "createDirectory" - {
    "should return in an existing path" in {
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

  "createDirectories" - {
    "should return in an existing path" in {
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

  "directoryStream" - {
    "returns an empty Stream on an empty directory" in {
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

    "returns all files in a directory correctly" in {
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
          .map { case (path, paths) => paths.exists(_.normalize === path.normalize) }
          .compile
          .fold(true)(_ & _)
          .unsafeRunSync() == true
      )
    }
  }

  "walk" - {
    "returns the only file in a directory correctly" in {
      assert(
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
          .length == 2 // the directory and the file
      )
    }

    "returns all files in a directory correctly" in {
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
          .map { case (path, paths) => paths.exists(_.normalize === path.normalize) }
          .compile
          .fold(true)(_ & _)
          .unsafeRunSync() == true // the directory itself and the files
      )
    }

    "returns all files in a nested tree correctly" in {
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

  "writeRotate" in {
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
              .asserting { sizes =>
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
