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

import cats.effect.{Blocker, IO}
import cats.syntax.all._
import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import scala.concurrent.duration._

class BaseFileSuite extends Fs2Suite {
  protected def tempDirectory: Stream[IO, Path] =
    Stream.bracket(IO(Files.createTempDirectory("BaseFileSpec")))(deleteDirectoryRecursively(_))

  protected def tempFile: Stream[IO, Path] =
    tempDirectory.flatMap(dir => aFile(dir))

  protected def tempFiles(count: Int): Stream[IO, List[Path]] =
    tempDirectory.flatMap(dir => aFile(dir).replicateA(count))

  protected def tempFilesHierarchy: Stream[IO, Path] =
    tempDirectory.flatMap { topDir =>
      Stream
        .eval(IO(Files.createTempDirectory(topDir, "BaseFileSpec")))
        .repeatN(5)
        .flatMap(dir =>
          Stream.eval(IO(Files.createTempFile(dir, "BaseFileSpecSub", ".tmp")).replicateA(5))
        )
        .drain ++ Stream.emit(topDir)
    }

  protected def aFile(dir: Path): Stream[IO, Path] =
    Stream.eval(IO(Files.createTempFile(dir, "BaseFileSpec", ".tmp")))

  protected def modify(file: Path): Stream[IO, Unit] =
    Stream.eval(IO(Files.write(file, Array[Byte](0, 1, 2, 3))).void)

  protected def modifyLater(file: Path, blocker: Blocker): Stream[IO, Unit] =
    Stream
      .range(0, 4)
      .map(_.toByte)
      .covary[IO]
      .metered(250.millis)
      .through(writeAll(file, blocker, StandardOpenOption.APPEND :: Nil))

  protected def deleteDirectoryRecursively(dir: Path): IO[Unit] =
    IO {
      Files.walkFileTree(
        dir,
        new SimpleFileVisitor[Path] {
          override def visitFile(path: Path, attrs: BasicFileAttributes) = {
            Files.delete(path)
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(path: Path, e: IOException) = {
            Files.delete(path)
            FileVisitResult.CONTINUE
          }
        }
      )
      ()
    }
}
