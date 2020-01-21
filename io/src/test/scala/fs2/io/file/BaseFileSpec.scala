package fs2
package io
package file

import cats.effect.{Blocker, IO}
import cats.implicits._
import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import scala.concurrent.duration._

class BaseFileSpec extends Fs2Spec {
  protected def tempDirectory: Stream[IO, Path] =
    Stream.bracket(IO(Files.createTempDirectory("BaseFileSpec")))(deleteDirectoryRecursively(_))

  protected def tempFile: Stream[IO, Path] =
    tempDirectory.flatMap(dir => aFile(dir))

  protected def tempFiles(count: Int): Stream[IO, List[Path]] =
    tempDirectory.flatMap(dir => aFile(dir).replicateA(count))

  protected def tempFilesHierarchy: Stream[IO, Path] = tempDirectory.flatMap { topDir =>
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

  protected def modifyLater(file: Path, blocker: Blocker): Stream[IO, Nothing] =
    Stream
      .range(0, 4)
      .map(_.toByte)
      .covary[IO]
      .metered(250.millis)
      .through(writeAll(file, blocker, StandardOpenOption.APPEND :: Nil))

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
}
