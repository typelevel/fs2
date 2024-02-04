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

import cats.effect.kernel.Sync
import cats.effect.std.Dispatcher

import java.nio.file.{Files => JFiles, Path => JPath, _}
import java.nio.file.attribute.{BasicFileAttributes => JBasicFileAttributes}

import fs2.concurrent.Channel
import fs2.io.CollectionCompat._

private[file] trait AsyncFilesPlatform[F[_]] { self: Files.UnsealedFiles[F] =>

  override def walk(
      start: Path,
      maxDepth: Int,
      followLinks: Boolean,
      chunkSize: Int
  ): Stream[F, Path] =
    if (chunkSize == Int.MaxValue) walkEager(start, maxDepth, followLinks)
    else walkLazy(start, maxDepth, followLinks, chunkSize)

  private def walkLazy(
      start: Path,
      maxDepth: Int,
      followLinks: Boolean,
      chunkSize: Int
  ): Stream[F, Path] =
    Stream.resource(Dispatcher.sequential[F]).flatMap { dispatcher =>
      Stream.eval(Channel.bounded[F, Chunk[Path]](10)).flatMap { channel =>
        val doWalk = Sync[F].interruptibleMany {
          val bldr = Vector.newBuilder[Path]
          var size = 0
          bldr.sizeHint(chunkSize)
          JFiles.walkFileTree(
            start.toNioPath,
            if (followLinks) Set(FileVisitOption.FOLLOW_LINKS).asJava else Set.empty.asJava,
            maxDepth,
            new SimpleFileVisitor[JPath] {
              private def enqueue(path: JPath): FileVisitResult = {
                bldr += Path.fromNioPath(path)
                size += 1
                if (size >= chunkSize) {
                  val result = dispatcher.unsafeRunSync(channel.send(Chunk.from(bldr.result())))
                  bldr.clear()
                  size = 0
                  if (result.isRight) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                } else FileVisitResult.CONTINUE
              }

              override def visitFile(file: JPath, attrs: JBasicFileAttributes): FileVisitResult =
                enqueue(file)

              override def visitFileFailed(file: JPath, t: IOException): FileVisitResult =
                FileVisitResult.CONTINUE

              override def preVisitDirectory(dir: JPath, attrs: JBasicFileAttributes)
                  : FileVisitResult =
                enqueue(dir)

              override def postVisitDirectory(dir: JPath, t: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
            }
          )

          dispatcher.unsafeRunSync(
            if (size > 0) channel.closeWithElement(Chunk.from(bldr.result()))
            else channel.close
          )
        }
        channel.stream.unchunks.concurrently(Stream.eval(doWalk))
      }
    }

  private def walkEager(start: Path, maxDepth: Int, followLinks: Boolean): Stream[F, Path] = {
    val doWalk = Sync[F].interruptibleMany {
      val bldr = Vector.newBuilder[Path]
      JFiles.walkFileTree(
        start.toNioPath,
        if (followLinks) Set(FileVisitOption.FOLLOW_LINKS).asJava else Set.empty.asJava,
        maxDepth,
        new SimpleFileVisitor[JPath] {
          private def enqueue(path: JPath): FileVisitResult = {
            bldr += Path.fromNioPath(path)
            FileVisitResult.CONTINUE
          }

          override def visitFile(file: JPath, attrs: JBasicFileAttributes): FileVisitResult =
            enqueue(file)

          override def visitFileFailed(file: JPath, t: IOException): FileVisitResult =
            FileVisitResult.CONTINUE

          override def preVisitDirectory(dir: JPath, attrs: JBasicFileAttributes): FileVisitResult =
            enqueue(dir)

          override def postVisitDirectory(dir: JPath, t: IOException): FileVisitResult =
            FileVisitResult.CONTINUE
        }
      )
      Chunk.from(bldr.result())
    }
    Stream.eval(doWalk).flatMap(Stream.chunk)
  }
}
