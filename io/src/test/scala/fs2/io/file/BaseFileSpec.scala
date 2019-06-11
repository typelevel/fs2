package fs2
package io
package file

import cats.effect.IO
import cats.implicits._
import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import scala.concurrent.Future

class BaseFileSpec extends Fs2Spec {

  protected def tempDirectory: Stream[IO, Path] =
    Stream.bracket(IO(Files.createTempDirectory("BaseFileSpec")))(deleteDirectoryRecursively(_))

  protected def tempFile: Stream[IO, Path] =
    tempDirectory.flatMap(dir => aFile(dir))

  protected def aFile(dir: Path): Stream[IO, Path] =
    Stream.eval(IO(Files.createTempFile(dir, "BaseFileSpec", ".tmp")))

  protected def modify(file: Path): Stream[IO, Unit] =
    Stream.eval(IO(Files.write(file, Array[Byte](0, 1, 2, 3))).void)

  protected def modifyLater(file: Path): Stream[IO, Unit] =
    Stream.eval(IO(Future(List[Byte](0, 1, 2, 3).foreach(waitAndAppend(file)))).void)

  protected def deleteDirectoryRecursively(dir: Path): IO[Unit] = IO {
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

  private def waitAndAppend(file: Path)(b: Byte) = {
    Thread.sleep(500)
    Files.write(file, Array(b), StandardOpenOption.APPEND)
    ()
  }

}
