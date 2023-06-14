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

import cats.Show
import cats.effect.FileDescriptorPoller
import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import fs2.io.internal.NativeUtil._

import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[fs2] trait ioplatform extends iojvmnative {

  private[fs2] def fileDescriptorPoller[F[_]: LiftIO]: F[FileDescriptorPoller] =
    IO.pollers
      .flatMap(
        _.collectFirst { case poller: FileDescriptorPoller => poller }.liftTo[IO](
          new RuntimeException("Installed PollingSystem does not provide a FileDescriptorPoller")
        )
      )
      .to

  //
  // STDIN/STDOUT Helpers

  /** Stream of bytes read asynchronously from standard input. */
  def stdin[F[_]: Async: LiftIO](bufSize: Int): Stream[F, Byte] =
    if (LinktimeInfo.isLinux || LinktimeInfo.isMac)
      Stream
        .resource {
          Resource
            .eval {
              setNonBlocking(STDIN_FILENO) *> fileDescriptorPoller[F]
            }
            .flatMap { poller =>
              poller.registerFileDescriptor(STDIN_FILENO, true, false).mapK(LiftIO.liftK)
            }
        }
        .flatMap { handle =>
          Stream.repeatEval {
            handle
              .pollReadRec(()) { _ =>
                IO {
                  val buf = new Array[Byte](bufSize)
                  val readed = guard(read(STDIN_FILENO, buf.at(0), bufSize.toULong))
                  if (readed > 0)
                    Right(Some(Chunk.array(buf, 0, readed)))
                  else if (readed == 0)
                    Right(None)
                  else
                    Left(())
                }
              }
              .to
          }
        }
        .unNoneTerminate
        .unchunks
    else
      readInputStream(Sync[F].blocking(System.in), bufSize, false)

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  def stdout[F[_]: Async: LiftIO]: Pipe[F, Byte, Nothing] =
    if (LinktimeInfo.isLinux || LinktimeInfo.isMac)
      writeFd(STDOUT_FILENO)
    else
      writeOutputStream(Sync[F].blocking(System.out), false)

  /** Pipe of bytes that writes emitted values to standard error asynchronously. */
  def stderr[F[_]: Async: LiftIO]: Pipe[F, Byte, Nothing] =
    if (LinktimeInfo.isLinux || LinktimeInfo.isMac)
      writeFd(STDERR_FILENO)
    else
      writeOutputStream(Sync[F].blocking(System.err), false)

  private[this] def writeFd[F[_]: Async: LiftIO](fd: Int): Pipe[F, Byte, Nothing] = in =>
    Stream
      .resource {
        Resource
          .eval {
            setNonBlocking(fd) *> fileDescriptorPoller[F]
          }
          .flatMap { poller =>
            poller.registerFileDescriptor(fd, false, true).mapK(LiftIO.liftK)
          }
      }
      .flatMap { handle =>
        in.chunks.foreach { bytes =>
          val Chunk.ArraySlice(buf, offset, length) = bytes.toArraySlice

          def go(pos: Int): IO[Either[Int, Unit]] =
            IO(write(fd, buf.at(offset + pos), (length - pos).toULong)).flatMap { wrote =>
              if (wrote >= 0) {
                val newPos = pos + wrote
                if (newPos < length)
                  go(newPos)
                else
                  IO.pure(Either.unit)
              } else
                IO.pure(Left(pos))
            }

          handle.pollWriteRec(0)(go(_)).to
        }
      }

  /** Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    */
  def stdoutLines[F[_]: Async: LiftIO, O: Show](
      charset: Charset = StandardCharsets.UTF_8
  ): Pipe[F, O, Nothing] =
    _.map(_.show).through(text.encode(charset)).through(stdout(implicitly, implicitly))

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  def stdinUtf8[F[_]: Async: LiftIO](bufSize: Int): Stream[F, String] =
    stdin(bufSize).through(text.utf8.decode)

  @deprecated("Prefer non-blocking, async variant", "3.5.0")
  def stdin[F[_], SourceBreakingDummy](bufSize: Int, F: Sync[F]): Stream[F, Byte] =
    readInputStream(F.blocking(System.in), bufSize, false)(F)

  @deprecated("Prefer non-blocking, async variant", "3.5.0")
  def stdout[F[_], SourceBreakingDummy](F: Sync[F]): Pipe[F, Byte, Nothing] =
    writeOutputStream(F.blocking(System.out: OutputStream), false)(F)

  @deprecated("Prefer non-blocking, async variant", "3.5.0")
  def stderr[F[_], SourceBreakingDummy](F: Sync[F]): Pipe[F, Byte, Nothing] =
    writeOutputStream(F.blocking(System.err: OutputStream), false)(F)

  @deprecated("Prefer non-blocking, async variant", "3.5.0")
  def stdoutLines[F[_], O, SourceBreakingDummy](
      charset: Charset,
      F: Sync[F],
      O: Show[O]
  ): Pipe[F, O, Nothing] =
    _.map(O.show(_)).through(text.encode(charset)).through(stdout(F))

  @deprecated("Prefer non-blocking, async variant", "3.5.0")
  def stdinUtf8[F[_], SourceBreakingDummy](bufSize: Int, F: Sync[F]): Stream[F, String] =
    stdin(bufSize, F).through(text.utf8.decode)

}
