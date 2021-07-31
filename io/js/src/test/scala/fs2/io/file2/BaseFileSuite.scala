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
package file2

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._

import scala.concurrent.duration._

trait BaseFileSuite extends Fs2Suite {

  protected def tempDirectory: Resource[IO, Path] =
    fs2.io.file.Files[IO].mkdtemp(prefix = "BaseFileSpec").map(p => Path(p.toString()))

  protected def tempFile: Resource[IO, Path] =
    tempDirectory.evalMap(aFile)

  protected def tempFiles(count: Int): Resource[IO, List[Path]] =
    tempDirectory.evalMap(aFile(_).replicateA(count))

  protected def tempFilesHierarchy: Resource[IO, Path] =
    tempDirectory.evalMap { topDir =>
      List
        .fill(5)(
          fs2.io.file.Files[IO]
            .mkdtemp(fs2.io.file.Path(topDir.toString), "BaseFileSpec")
            .allocated
            .map(_._1)
        )
        .traverse {
          _.map(p => Path(p.toString())).flatMap { dir =>
            List
              .tabulate(5)(i =>
                Files[IO].open(dir / s"BaseFileSpecSub$i.tmp", Flags.Write).use(_ => IO.unit)
              )
              .sequence
          }
        }
        .as(topDir)
    }

  protected def aFile(dir: Path): IO[Path] =
    fs2.io.file.Files[IO]
      .mkdtemp(fs2.io.file.Path(dir.toString))
      .map(p => Path(p.toString()))
      .allocated
      .map(_._1)
      .map(_ / "BaseFileSpec.tmp")
      .flatTap(Files[IO].open(_, Flags.Write).use(_ => IO.unit))

  protected def modify(file: Path): IO[Path] =
    Files[IO]
      .writeAll(file)
      .apply(Stream.emits(Array[Byte](0, 1, 2, 3)))
      .compile
      .drain
      .as(file)

  protected def modifyLater(file: Path): Stream[IO, INothing] =
    Stream
      .range(0, 4)
      .map(_.toByte)
      .covary[IO]
      .metered(250.millis)
      .through(Files[IO].writeAll(file, Flags(Flag.Append)))

  protected def deleteDirectoryRecursively(dir: Path): IO[Unit] =
    fs2.io.file.Files[IO].rm(fs2.io.file.Path(dir.toString), recursive = true)
}
