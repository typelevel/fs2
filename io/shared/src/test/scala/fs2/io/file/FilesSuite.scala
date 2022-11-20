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

import cats.effect.{IO, Resource}
import cats.kernel.Order
import cats.syntax.all._

import scala.concurrent.duration._

class FilesSuite extends Fs2IoSuite with BaseFileSuite {

  group("readAll") {
    test("retrieves whole content of a file") {
      Stream
        .resource(tempFile.evalMap(modify))
        .flatMap(path => Files[IO].readAll(path))
        .map(_ => 1)
        .compile
        .foldMonoid
        .assertEquals(4)
    }

    test("suspends errors in the effect") {
      Stream
        .eval(tempFile.use(IO.pure))
        .flatMap { path =>
          Files[IO]
            .readAll(path)
            .drain
            .void
            .recover { case ex: NoSuchFileException =>
              assertEquals(ex.getMessage, path.toString)
            }
        }
        .compile
        .drain

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
    test("can handle Long range endpoints") {
      Stream
        .resource(tempFile.evalMap(modify))
        .flatMap(path => Files[IO].readRange(path, 4096, 0, Long.MaxValue))
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
            .through(text.utf8.encode)
            .through(Files[IO].writeAll(path)) ++ Files[IO]
            .readAll(path)
            .through(text.utf8.decode)
        }
        .compile
        .foldMonoid
        .assertEquals("Hello world!")
    }

    test("append") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          val src = Stream("Hello", " world!").covary[IO].through(text.utf8.encode)
          src.through(Files[IO].writeAll(path)) ++
            src.through(Files[IO].writeAll(path, Flags.Append)) ++ Files[IO]
              .readAll(path)
              .through(text.utf8.decode)
        }
        .compile
        .foldMonoid
        .assertEquals("Hello world!Hello world!")
    }

    test("suspends errors in the effect") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          (Stream("Hello", " world!")
            .covary[IO]
            .through(text.utf8.encode)
            .through(Files[IO].writeAll(path, Flags(Flag.Write, Flag.CreateNew))) ++ Files[IO]
            .readAll(path)
            .through(text.utf8.decode)).void
            .recover { case ex: FileAlreadyExistsException =>
              assertEquals(ex.getMessage(), path.toString)
            }
        }
        .compile
        .drain
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
      Files[IO].exists(Path("nothing")).assertEquals(false)
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
        .getPosixPermissions(Path("nothing"))
        .void
        .recover { case ex: NoSuchFileException =>
          assertEquals(ex.getMessage, "nothing")
        }
    }
    test("should return permissions for existing file") {
      val permissions = PosixPermissions.fromString("rwxrwxr-x").get
      tempFile
        .use { p =>
          Files[IO].setPosixPermissions(p, permissions) >>
            Files[IO].getPosixPermissions(p)
        }
        .assertEquals(permissions)
    }
    test("innappropriate access should raise AccessDeniedException") {
      val permissions = PosixPermissions.fromString("r--r--r--").get
      tempFile
        .use { p =>
          (Files[IO].setPosixPermissions(p, permissions) >>
            Files[IO].open(p, Flags.Write).use_.void).recover { case ex: AccessDeniedException =>
            assertEquals(ex.getMessage, p.toString)
          }
        }
    }
  }

  group("setPermissions") {
    test("should fail for a non existent file") {
      Files[IO]
        .setPosixPermissions(Path("nothing"), PosixPermissions())
        .void
        .recover { case ex: NoSuchFileException =>
          assertEquals(ex.getMessage, "nothing")
        }
    }
    test("should correctly change file permissions for existing file") {
      val permissions = PosixPermissions.fromString("rwxrwxr-x").get
      tempFile
        .use { p =>
          for {
            initialPermissions <- Files[IO].getPosixPermissions(p)
            _ <- Files[IO].setPosixPermissions(p, permissions)
            updatedPermissions <- Files[IO].getPosixPermissions(p)
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
          val target = tempDir / "newfile"
          Files[IO]
            .copy(filePath, target) >>
            Files[IO].exists(target)
        }
        .assertEquals(true)
    }

    test("recursive copy in a streaming fashion") {
      (tempFilesHierarchy, tempDirectory).tupled
        .use { case (from, to) =>
          Files[IO]
            .walk(from)
            .tail
            .evalMap { source =>
              val dest = to / from.relativize(source)
              Files[IO]
                .isDirectory(source)
                .ifM(
                  Files[IO].createDirectory(dest),
                  Files[IO].copy(source, dest)
                ) >> Files[IO].exists(dest).assertEquals(true)
            }
            .compile
            .drain
        }
    }
  }

  group("delete") {
    test("should fail on a non existent file") {
      Files[IO]
        .delete(Path("nothing"))
        .recover { case ex: NoSuchFileException =>
          assertEquals(ex.getMessage, "nothing")
        }
    }
    test("should fail on a non empty directory") {
      tempFilesHierarchy.use { p =>
        Files[IO].delete(p).recover { case ex: DirectoryNotEmptyException =>
          assertEquals(ex.getMessage, p.toString)
        }
      }
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

  group("deleteDirectoryRecursively") {
    test("should remove a non-empty directory") {
      val testPath = Path("a")
      Files[IO].createDirectories(testPath / "b" / "c") >>
        Files[IO].deleteRecursively(testPath) >>
        Files[IO].exists(testPath).assertEquals(false)
    }
  }

  group("move") {
    test("should result in the old path being deleted") {
      (tempFile, tempDirectory).tupled
        .use { case (filePath, tempDir) =>
          Files[IO].move(filePath, tempDir / "newfile") >>
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
          Files[IO].tempFile
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
        .resource(Files[IO].tempFile)
        .evalMap(Files[IO].delete)
        .compile
        .lastOrError
    }

    test("should create the file in the specified directory") {
      tempDirectory
        .use { tempDir =>
          val files = Files[IO]
          files
            .tempFile(Some(tempDir), "", "", None)
            .use { file =>
              // files.exists(tempDir / file.fileName)
              IO.pure(file.startsWith(tempDir))
            }
        }
        .assertEquals(true)
    }

    test("should create the file in the default temp directory when dir is not specified") {

      val files = Files[IO]

      files.tempFile
        .use { file =>
          defaultTempDirectory.map(dir =>
            // files.exists(dir / file.fileName)
            file.startsWith(dir)
          )
        }
        .assertEquals(true)
    }
  }

  group("tempDirectoryStream") {
    test("should remove the directory following stream closure") {
      Stream
        .resource {
          Files[IO].tempDirectory
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
        .resource(Files[IO].tempDirectory)
        .evalMap(Files[IO].delete)
        .compile
        .lastOrError
    }

    test("should create the directory in the specified directory") {
      tempDirectory
        .use { tempDir =>
          val files = Files[IO]
          files
            .tempDirectory(Some(tempDir), "", None)
            .use { directory =>
              files.exists(tempDir.resolve(directory.fileName))
            }
        }
        .assertEquals(true)
    }

    test("should create the directory in the default temp directory when dir is not specified") {

      val files = Files[IO]

      files.tempDirectory
        .use { directory =>
          defaultTempDirectory.flatMap(dir => files.exists(dir / directory.fileName))
        }
        .assertEquals(true)
    }
  }

  group("createDirectory") {
    test("should return in an existing path") {
      tempDirectory
        .use { path =>
          val temp = path / "temp"
          Files[IO]
            .createDirectory(temp)
            .bracket(_ => Files[IO].exists(temp))(_ => Files[IO].deleteIfExists(temp).void)
        }
        .assertEquals(true)
    }
  }

  group("createDirectories") {
    test("should return in an existing path") {
      tempDirectory
        .use { path =>
          val inner = path / "temp" / "inner"
          Files[IO]
            .createDirectories(inner)
            .bracket(_ => Files[IO].exists(inner))(_ => Files[IO].deleteIfExists(inner).void)
        }
        .assertEquals(true)
    }
  }

  group("list") {
    test("returns an empty Stream on an empty directory") {
      tempDirectory
        .use { path =>
          Files[IO]
            .list(path)
            .compile
            .last
        }
        .assertEquals(None)
    }

    test("returns all files in a directory correctly") {
      Stream
        .resource(tempFiles(10))
        .flatMap { paths =>
          val parent = paths.head.parent.get
          Files[IO]
            .list(parent)
            .map(path => paths.exists(_.normalize == path.normalize))
        }
        .compile
        .fold(true)(_ & _)
        .assertEquals(true)
    }

    test("fail for non-directory") {
      Stream
        .resource(tempFile)
        .flatMap { p =>
          Files[IO].list(p).void.recover { case ex: NotDirectoryException =>
            assertEquals(ex.getMessage, p.toString)
          }
        }
        .compile
        .drain
    }
  }

  group("walk") {
    test("returns the only file in a directory correctly") {
      Stream
        .resource(tempFile)
        .flatMap { path =>
          Files[IO].walk(path.parent.get).map(_.normalize == path.normalize)
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
          val parent = paths.head.parent.get
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
            .through(Files[IO].writeRotate(path, rotateLimit.toLong, Flags.Append))

          val verify = Files[IO]
            .list(dir)
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
          .sortBy { case (path, _) => path }(Order[Path].toOrdering)
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

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isDirectory(_))
        .assertEquals(false)
    }
  }

  group("isFile") {
    test("returns true if the path is for a file") {
      tempFile
        .use(Files[IO].isRegularFile(_))
        .assertEquals(true)
    }

    test("returns false if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isRegularFile(_))
        .assertEquals(false)
    }

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isRegularFile(_))
        .assertEquals(false)
    }
  }

  group("isReadable") {
    test("returns true if the path is for a readable file") {
      tempFile
        .use(Files[IO].isReadable(_))
        .assertEquals(true)
    }

    test("returns true if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isReadable(_))
        .assertEquals(true)
    }

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isReadable(_))
        .assertEquals(false)
    }
  }

  group("isWritable") {
    test("returns true if the path is for a readable file") {
      tempFile
        .use(Files[IO].isWritable(_))
        .assertEquals(true)
    }

    test("returns true if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isWritable(_))
        .assertEquals(true)
    }

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isWritable(_))
        .assertEquals(false)
    }
  }

  group("isHidden") {

    test("returns false if the path is for a non-hidden file") {
      tempFile
        .use(Files[IO].isHidden(_))
        .assertEquals(false)
    }

    test("returns false if the path is for a non-hidden directory") {
      tempDirectory
        .use(Files[IO].isHidden(_))
        .assertEquals(false)
    }

    test("returns true if the path is for a hidden file") {
      val hiddenFile = for {
        dir <- tempDirectory
        hiddenFilePath = dir.resolve(".should-be-hidden")
        _ <- Resource.make(Files[IO].createFile(hiddenFilePath)) { _ =>
          Files[IO].deleteIfExists(hiddenFilePath).void
        }
      } yield hiddenFilePath
      hiddenFile
        .use(Files[IO].isHidden(_))
        .assertEquals(true)
    }

    test("returns false if the path is for a non-hidden file in a hidden directory") {
      val fileInHiddenDir = for {
        dir <- tempDirectory
        hiddenDirPath = dir.resolve(".should-be-hidden")
        _ <- Resource.make(Files[IO].createDirectory(hiddenDirPath)) { _ =>
          Files[IO].deleteIfExists(hiddenDirPath).void
        }
        filePath = hiddenDirPath.resolve("not-hidden")
        _ <- Resource.make(Files[IO].createFile(filePath)) { _ =>
          Files[IO].deleteIfExists(filePath).void
        }
      } yield filePath
      fileInHiddenDir
        .use(Files[IO].isHidden(_))
        .assertEquals(false)
    }

    test("returns true if the path is for a hidden directory") {
      val hiddenDir = for {
        dir <- tempDirectory
        hiddenDirPath = dir.resolve(".should-be-hidden")
        _ <- Resource.make(Files[IO].createDirectory(hiddenDirPath)) { _ =>
          Files[IO].deleteIfExists(hiddenDirPath).void
        }
      } yield hiddenDirPath
      hiddenDir
        .use(Files[IO].isHidden(_))
        .assertEquals(true)
    }

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isWritable(_))
        .assertEquals(false)
    }
  }

  group("isSymbolicLink") {

    test("returns true if the path is for a symbolic link") {
      val symLink = for {
        dir <- tempDirectory
        file <- tempFile
        symLinkPath = dir.resolve("temp-sym-link")
        _ <- Resource.make(Files[IO].createSymbolicLink(symLinkPath, file)) { _ =>
          Files[IO].deleteIfExists(symLinkPath).void
        }
      } yield symLinkPath
      symLink
        .use(Files[IO].isSymbolicLink(_))
        .assertEquals(true)
    }

    test("returns false if the path is for a directory") {
      tempDirectory
        .use(Files[IO].isSymbolicLink(_))
        .assertEquals(false)
    }

    test("returns false if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].isSymbolicLink(_))
        .assertEquals(false)
    }
  }

  group("realPath") {

    test("doesn't fail if the path is for a file") {
      tempFile
        .use(Files[IO].realPath(_))
        .void
        .assert
    }

    test("doesn't fail if the path is for a directory") {
      tempDirectory
        .use(Files[IO].realPath(_))
        .void
        .assert
    }

    test("fails with NoSuchFileException if the path does not exist") {
      tempDirectory
        .map(_.resolve("non-existent-file"))
        .use(Files[IO].realPath(_))
        .intercept[NoSuchFileException]
    }
  }

}
