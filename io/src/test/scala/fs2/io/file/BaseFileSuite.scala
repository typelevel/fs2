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
import cats.syntax.all._

import java.io.IOException
import java.nio.file.{Files => JFiles, _}
import java.nio.file.attribute.BasicFileAttributes

import scala.concurrent.duration._

trait BaseFileSuite {
  protected def tempDirectory: Resource[IO, Path] =
    Resource.make(IO(JFiles.createTempDirectory("BaseFileSpec")))(deleteDirectoryRecursively(_))

  protected def tempFile: Resource[IO, Path] =
    tempDirectory.evalMap(aFile)

  protected def tempFiles(count: Int): Resource[IO, List[Path]] =
    tempDirectory.evalMap(aFile(_).replicateA(count))

  protected def tempFilesHierarchy: Resource[IO, Path] =
    tempDirectory.evalMap { topDir =>
      List
        .fill(5)(IO(JFiles.createTempDirectory(topDir, "BaseFileSpec")))
        .traverse {
          _.flatMap { dir =>
            IO(JFiles.createTempFile(dir, "BaseFileSpecSub", ".tmp")).replicateA(5)
          }
        }
        .as(topDir)
    }

  protected def aFile(dir: Path): IO[Path] =
    IO(JFiles.createTempFile(dir, "BaseFileSpec", ".tmp"))

  protected def modify(file: Path): IO[Path] =
    IO(JFiles.write(file, Array[Byte](0, 1, 2, 3))).as(file)

  protected def modifyLater(file: Path): Stream[IO, INothing] =
    Stream
      .range(0, 4)
      .map(_.toByte)
      .covary[IO]
      .metered(250.millis)
      .through(Files[IO].writeAll(file, StandardOpenOption.APPEND :: Nil))

  protected def deleteDirectoryRecursively(dir: Path): IO[Unit] =
    IO {
      JFiles.walkFileTree(
        dir,
        new SimpleFileVisitor[Path] {
          override def visitFile(path: Path, attrs: BasicFileAttributes) = {
            JFiles.delete(path)
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(path: Path, e: IOException) = {
            JFiles.delete(path)
            FileVisitResult.CONTINUE
          }
        }
      )
    }.void
}
