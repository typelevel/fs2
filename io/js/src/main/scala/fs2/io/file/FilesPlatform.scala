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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.internal.jsdeps.node.eventsMod
import fs2.internal.jsdeps.node.fsMod
import fs2.internal.jsdeps.node.fsPromisesMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.io.file.Files.UnsealedFiles

import scala.scalajs.js

private[file] trait FilesPlatform[F[_]]

private[fs2] trait FilesCompanionPlatform {

  implicit def forAsync[F[_]: Async]: Files[F] = new NodeFiles[F]

  private final class NodeFiles[F[_]](implicit F: Async[F]) extends UnsealedFiles[F] {
    private def combineFlags(flags: Flags): Double =
      Flag.monoid.combineAll(flags.value).bits.toDouble

    override def copy(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.copyFile(
            source.toString,
            target.toString,
            CopyFlag.monoid.combineAll(flags.value).jsBits.toDouble
          )
        )
      )

    override def createDirectory(path: Path): F[Unit] =
      ???

    override def createDirectories(path: Path): F[Unit] =
      ???
 
    override def delete(path: Path): F[Unit] =
      ???

    override def deleteIfExists(path: Path): F[Boolean] =
      ???

    override def deleteRecursively(
        path: Path,
        followLinks: Boolean
    ): F[Unit] = ???

    override def exists(path: Path, followLinks: Boolean): F[Boolean] = ???

    override def open(path: Path, flags: Flags): Resource[F, FileHandle[F]] = Resource
      .make(
        F.fromPromise(
          F.delay(fsPromisesMod.open(path.toString, combineFlags(flags)))
        )
      )(fd => F.fromPromise(F.delay(fd.close())))
      .map(FileHandle.make[F])
      .adaptError { case IOException(ex) => ex }

    private def readStream(path: Path, chunkSize: Int, flags: Flags)(
        f: fsMod.ReadStreamOptions => fsMod.ReadStreamOptions
    ): Stream[F, Byte] =
      readReadable(
        F.async_[Readable] { cb =>
          val rs = fsMod
            .createReadStream(
              path.toString,
              f(
                js.Dynamic
                  .literal(flags = combineFlags(flags))
                  .asInstanceOf[fsMod.ReadStreamOptions]
                  .setHighWaterMark(chunkSize.toDouble)
              )
            )
          rs.once_ready(
            nodeStrings.ready,
            () => {
              rs.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
              cb(Right(rs.asInstanceOf[Readable]))
            }
          )
          rs.once_error(
            nodeStrings.error,
            error => {
              rs.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
              cb(Left(js.JavaScriptException(error)))
            }
          )
        }
      ).adaptError { case IOException(ex) => ex }

    override def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      readStream(path, chunkSize, flags)(identity)

    override def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
      readStream(path, chunkSize, Flags.Read)(_.setStart(start.toDouble).setEnd((end - 1).toDouble))

    override def size(path: Path): F[Long] =
      F.fromPromise(F.delay(fsPromisesMod.stat(path.toString))).map(_.size.toLong)

    override def writeAll(path: Path, flags: Flags): Pipe[F, Byte, INothing] =
      in =>
        in.through {
          writeWritable(
            F.async_[Writable] { cb =>
              val ws = fsMod
                .createWriteStream(
                  path.toString,
                  js.Dynamic
                    .literal(flags = combineFlags(flags))
                    .asInstanceOf[fsMod.StreamOptions]
                )
              ws.once_ready(
                nodeStrings.ready,
                () => {
                  ws.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
                  cb(Right(ws.asInstanceOf[Writable]))
                }
              )
              ws.once_error(
                nodeStrings.error,
                error => {
                  ws.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
                  cb(Left(js.JavaScriptException(error)))
                }
              )
            }
          )
        }.adaptError { case IOException(ex) => ex }

  }

}
