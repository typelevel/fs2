/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package file

import java.nio.file.{Paths, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.IO
import cats.syntax.all._

import fs2.io.CollectionCompat._

import scala.concurrent.duration._

class FilesSuite extends Fs2Suite with BaseFileSuite {
  group("readAll") {
    test("retrieves whole content of a file") {
      Stream
        .resource(tempFile.evalMap(modify))
        .flatMap(path => Files[IO].readAll(path, 4096))
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(4)
    }
  }

  group("readRange") {
    test("reads half of a file") {
      Stream
        .resource(tempFile.evalMap(modify))
        .flatMap(path => Files[IO].readRange(path, 4096, 0, 2))
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(2)
    }
    test("reads full file if end is bigger than file size") {
      Stream
        .resource(tempFile.evalMap(modify))
        .flatMap(path => Files[IO].readRange(path, 4096, 0, 100))
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(4)
    }
  }

  group("writeAll") {
    test("simple write") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          Stream("Hello", " world!")
            .covary[IO]
            .through(text.utf8Encode)
            .through(Files[IO].writeAll(path)) ++ Files[IO]
            .readAll(path, 4096)
            .through(text.utf8Decode)
        }
        .compile
        .foldMonoid
        .assertEquals("Hello world!")
    }

    test("append") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          val src = Stream("Hello", " world!").covary[IO].through(text.utf8Encode)
          src.through(Files[IO].writeAll(path)) ++
            src.through(Files[IO].writeAll(path, List(StandardOpenOption.APPEND))) ++ Files[IO]
              .readAll(path, 4096)
              .through(text.utf8Decode)
        }
        .compile
        .foldMonoid
        .assertEquals("Hello world!Hello world!")
    }
  }

  group("tail") {
    test("keeps reading a file as it is appended") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          Files[IO]
            .tail(path, 4096, pollDelay = 25.millis)
            .concurrently(modifyLater(path))
        }
        .take(4)
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(4)
    }
  }

  group("exists") {
    test("returns false on a non existent file") {
      Files[IO].exists(Paths.get("nothing")).assertEquals(false)
    }
    test("returns true on an existing file") {
      tempFile
        .use(Files[IO].exists(_))
        .assertEquals(true)
    }
  }

  group("permissions") {
    test("should fail for a non existent file") {
      Files[IO]
        .permissions(Paths.get("nothing"))
        .intercept[Throwable]
    }
    test("should return permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      tempFile
        .use { p =>
          Files[IO].setPermissions(p, permissions) >>
            Files[IO].permissions(p)
        }
        .assertEquals(permissions)
    }
  }

  group("setPermissions") {
    test("should fail for a non existent file") {
      Files[IO]
        .setPermissions(Paths.get("nothing"), Set.empty)
        .intercept[Throwable]
    }
    test("should correctly change file permissions for existing file") {
      val permissions = PosixFilePermissions.fromString("rwxrwxr-x").asScala
      tempFile
        .use { p =>
          for {
            initialPermissions <- Files[IO].permissions(p)
            _ <- Files[IO].setPermissions(p, permissions)
            updatedPermissions <- Files[IO].permissions(p)
          } yield {
            assertNotEquals(initialPermissions, updatedPermissions)
            assertEquals(updatedPermissions, permissions)
          }
        }
    }
  }

  group("copy") {
    test("returns a path to the new file") {
      (tempFile, tempDirectory).tupled
        .use { case (filePath, tempDir) =>
          Files[IO]
            .copy(filePath, tempDir.resolve("newfile"))
            .flatMap(Files[IO].exists(_))
        }
        .assertEquals(true)
    }
  }

  group("deleteIfExists") {
    test("should result in non existent file") {
      tempFile
        .use { path =>
          Files[IO].delete(path) >> Files[IO].exists(path)
        }
        .assertEquals(false)
    }
  }

  group("delete") {
    test("should fail on a non existent file") {
      Files[IO]
        .delete(Paths.get("nothing"))
        .intercept[Throwable]
    }
  }

  group("deleteDirectoryRecursively") {
    test("should remove a non-empty directory") {
      val testPath = Paths.get("a")
      Files[IO].createDirectories(testPath.resolve("b/c")) >>
        Files[IO].deleteDirectoryRecursively(testPath) >>
        Files[IO].exists(testPath).assertEquals(false)
    }
  }

  group("move") {
    test("should result in the old path being deleted") {
      (tempFile, tempDirectory).tupled
        .use { case (filePath, tempDir) =>
          Files[IO].move(filePath, tempDir.resolve("newfile")) >>
            Files[IO].exists(filePath)
        }
        .assertEquals(false)
    }
  }

  group("size") {
    test("should return correct size of ay file") {
      tempFile
        .use { path =>
          modify(path) >> Files[IO].size(path)
        }
        .assertEquals(4L)
    }
  }

  group("tempFile") {
    test("should remove the file following stream closure") {
      Stream
        .resource {
          Files[IO]
            .tempFile(Some(Paths.get("")))
            .evalMap(path => Files[IO].exists(path).tupleRight(path))
        }
        .compile
        .lastOrError
        .flatMap { case (existsBefore, path) =>
          Files[IO]
            .exists(path)
            .tupleLeft(existsBefore)
            .assertEquals(true -> false)
        }
    }

    test("should not fail if the file is deleted before the stream completes") {
      Stream
        .resource(Files[IO].tempFile(Some(Paths.get(""))))
        .evalMap(Files[IO].delete)
        .compile
        .lastOrError
    }

    test("should create the file in the specified directory") {
      tempDirectory
        .use { tempDir =>
          val files = Files[IO]
          files
            .tempFile(Some(tempDir))
            .use { file =>
              files.exists(tempDir.resolve(file.getFileName))
            }
        }
        .map(it => assert(it))
    }

    test("should create the file in the default temp directory when dir is not specified") {

      val files = Files[IO]

      files
        .tempFile(None)
        .use { file =>
          IO(System.getProperty("java.io.tmpdir")).flatMap(dir =>
            files.exists(Paths.get(dir).resolve(file.getFileName))
          )
        }
        .map(existsInDefault => assert(existsInDefault))
    }
  }

  group("tempDirectoryStream") {
    test("should remove the directory following stream closure") {
      Stream
        .resource {
          Files[IO]
            .tempDirectory(Some(Paths.get("")))
            .evalMap(path => Files[IO].exists(path).tupleRight(path))
        }
        .compile
        .lastOrError
        .flatMap { case (existsBefore, path) =>
          Files[IO]
            .exists(path)
            .tupleLeft(existsBefore)
            .assertEquals(true -> false)
        }
    }

    test("should not fail if the directory is deleted before the stream completes") {
      Stream
        .resource(Files[IO].tempDirectory(Some(Paths.get(""))))
        .evalMap(Files[IO].delete)
        .compile
        .lastOrError
    }

    test("should create the directory in the specified directory") {
      tempDirectory
        .use { tempDir =>
          val files = Files[IO]
          files
            .tempDirectory(Some(tempDir))
            .use { directory =>
              files.exists(tempDir.resolve(directory.getFileName))
            }
        }
        .map(it => assert(it))
    }

    test("should create the directory in the default temp directory when dir is not specified") {

      val files = Files[IO]

      files
        .tempDirectory(None)
        .use { directory =>
          IO(System.getProperty("java.io.tmpdir")).flatMap(dir =>
            files.exists(Paths.get(dir).resolve(directory.getFileName))
          )
        }
        .map(existsInDefault => assert(existsInDefault))
    }
  }

  group("createDirectory") {
    test("should return in an existing path") {
      tempDirectory
        .use { path =>
          Files[IO]
            .createDirectory(path.resolve("temp"))
            .bracket(Files[IO].exists(_))(Files[IO].deleteIfExists(_).void)
        }
        .assertEquals(true)
    }
  }

  group("createDirectories") {
    test("should return in an existing path") {
      tempDirectory
        .use { path =>
          Files[IO]
            .createDirectories(path.resolve("temp/inner"))
            .bracket(Files[IO].exists(_))(Files[IO].deleteIfExists(_).void)
        }
        .assertEquals(true)
    }
  }

  group("directoryStream") {
    test("returns an empty Stream on an empty directory") {
      tempDirectory
        .use { path =>
          Files[IO]
            .directoryStream(path)
            .compile
            .last
        }
        .assertEquals(None)
    }

    test("returns all files in a directory correctly") {
      Stream
        .resource(tempFiles(10))
        .flatMap { paths =>
          val parent = paths.head.getParent
          Files[IO]
            .directoryStream(parent)
            .map(path => paths.exists(_.normalize == path.normalize))
        }
        .compile
        .fold(true)(_ & _)
        .assertEquals(true)
    }
  }

  group("walk") {
    test("returns the only file in a directory correctly") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          Files[IO].walk(path.getParent).map(_.normalize == path.normalize)
        }
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(2) // the directory and the file
    }

    test("returns all files in a directory correctly") {
      Stream
        .resource(tempFiles(10))
        .flatMap { paths =>
          val parent = paths.head.getParent
          Files[IO]
            .walk(parent)
            .map(path => (parent :: paths).exists(_.normalize == path.normalize))
        }
        .compile
        .fold(true)(_ & _)
        .assertEquals(true) // the directory itself and the files
    }

    test("returns all files in a nested tree correctly") {
      Stream
        .resource(tempFilesHierarchy)
        .flatMap(topDir => Files[IO].walk(topDir))
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(31) // the root + 5 children + 5 files per child directory
    }
  }

  test("writeRotate") {
    val bufferSize = 100
    val totalBytes = 1000
    val rotateLimit = 150
    Stream
      .resource(tempDirectory)
      .flatMap { dir =>
        Stream.eval(IO.ref(0)).flatMap { counter =>
          val path = counter.modify(i => (i + 1) -> dir.resolve(i.toString))

          val write = Stream(0x42.toByte).repeat
            .buffer(bufferSize)
            .take(totalBytes.toLong)
            .through(Files[IO].writeRotate(path, rotateLimit.toLong))

          val verify = Files[IO]
            .directoryStream(dir)
            .evalMap { path =>
              Files[IO].size(path).tupleLeft(path)
            }

          write ++ verify
        }
      }
      .compile
      .toList
      .map { results =>
        val sizes = results
          .sortBy { case (path, _) => path }
          .map { case (_, size) => size }

        assertEquals(sizes.size, (totalBytes + rotateLimit - 1) / rotateLimit)
        assert(sizes.init.forall(_ == rotateLimit))
        assertEquals(sizes.last, (totalBytes % rotateLimit).toLong)
      }
  }

  group("isDirectory") {
    test("returns false if the path is for a file") {
      tempFile
        .use(Files[IO].isDirectory(_))
        .assertEquals(false)
    }

    test("returns true if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isDirectory(_))
        .assertEquals(true)
    }
  }

  group("isFile") {
    test("returns true if the path is for a file") {
      tempFile
        .use(Files[IO].isFile(_))
        .assertEquals(true)
    }

    test("returns false if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isFile(_))
        .assertEquals(false)
    }
  }
}
