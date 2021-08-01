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

import cats.Monoid
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.internal.jsdeps.node.fsMod
import fs2.internal.jsdeps.node.fsPromisesMod
import fs2.io.file.Files.UnsealedFiles

private[file] trait FilesPlatform[F[_]]

private[fs2] trait FilesCompanionPlatform {

  implicit def forAsync[F[_]: Async]: Files[F] = new NodeFiles[F]

  private final class NodeFiles[F[_]](implicit F: Async[F]) extends UnsealedFiles[F] {

    override def copy(source: Path, target: Path, flags: CopyFlags): F[Unit] = ???
    // Note: JVM defaults to failing the copy if target exists, node defaults inverse

    private def combineFlags(flags: Flags): Double = flags.value
      .foldMap(_.bits)(new Monoid[Long] {
        override def combine(x: Long, y: Long): Long = x | y
        override def empty: Long = 0
      })
      .toDouble

    override def open(path: Path, flags: Flags): Resource[F, FileHandle[F]] = Resource
      .make(
        F.fromPromise(
          F.delay(fsPromisesMod.open(path.toString, combineFlags(flags)))
        )
      )(fd => F.fromPromise(F.delay(fd.close())))
      .map(FileHandle.make[F])

    private def readStream(path: Path, chunkSize: Int, flags: Flags)(
        f: fsMod.ReadStreamOptions => fsMod.ReadStreamOptions
    ): Stream[F, Byte] =
      Stream.resource(open(path, flags)).flatMap { handle =>
        readReadable(
          F.delay(
            fsMod
              .createReadStream(
                path.toString,
                f(fsMod.ReadStreamOptions().setFd(handle.fd).setHighWaterMark(chunkSize.toDouble))
              )
              .asInstanceOf[Readable]
          )
        )
      }

    override def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      readStream(path, chunkSize, flags)(identity)

    override def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
      readStream(path, chunkSize, Flags.Read)(_.setStart(start.toDouble).setEnd((end - 1).toDouble))

    override def size(path: Path): F[Long] = ???

    override def writeAll(path: Path, flags: Flags): Pipe[F, Byte, INothing] =
      in =>
        Stream.resource(open(path, flags)).flatMap { handle =>
          in.through {
            writeWritable(
              F.delay(
                fsMod
                  .createWriteStream(
                    path.toString,
                    fsMod.StreamOptions().setFd(handle.fd)
                  )
                  .asInstanceOf[Writable]
              )
            )
          }
        }

  }

}
