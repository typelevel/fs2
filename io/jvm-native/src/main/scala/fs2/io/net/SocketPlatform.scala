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
package net

import com.comcast.ip4s.{IpAddress, SocketAddress}
import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.syntax.all._

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousSocketChannel, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}

private[net] trait SocketCompanionPlatform {

  /** Creates a [[Socket]] instance for given `AsynchronousSocketChannel`
    * with 16 KiB max. read chunk size and exclusive access guards for both reads abd writes.
    */
  private[net] def forAsync[F[_]: Async](
      ch: AsynchronousSocketChannel
  ): F[Socket[F]] =
    forAsync(ch, maxReadChunkSize = 16384, withExclusiveReads = true, withExclusiveWrites = true)

  /** Creates a [[Socket]] instance for given `AsynchronousSocketChannel`.
    *
    * @param ch async socket channel for actual reads and writes
    * @param maxReadChunkSize maximum chunk size for [[Socket#reads]] method
    * @param withExclusiveReads set to `true` if reads should be guarded by mutex
    * @param withExclusiveWrites set to `true` if writes should be guarded by mutex
    */
  private[net] def forAsync[F[_]](
      ch: AsynchronousSocketChannel,
      maxReadChunkSize: Int,
      withExclusiveReads: Boolean = false,
      withExclusiveWrites: Boolean = false
  )(implicit F: Async[F]): F[Socket[F]] = {
    def maybeMutex(maybe: Boolean) = F.defer(if (maybe) Mutex[F].map(Some(_)) else F.pure(None))
    (maybeMutex(withExclusiveReads), maybeMutex(withExclusiveWrites)).mapN {
      (readMutex, writeMutex) => new AsyncSocket[F](ch, readMutex, writeMutex, maxReadChunkSize)
    }
  }

  private[net] abstract class BufferedReads[F[_]](
      readMutex: Option[Mutex[F]],
      writeMutex: Option[Mutex[F]],
      maxReadChunkSize: Int
  )(implicit F: Async[F])
      extends Socket[F] {
    private def lock(mutex: Option[Mutex[F]]): Resource[F, Unit] =
      mutex match {
        case Some(mutex) => mutex.lock
        case None        => Resource.unit
      }

    private def withReadBuffer[A](size: Int)(f: ByteBuffer => F[A]): F[A] =
      lock(readMutex).surround {
        F.delay(ByteBuffer.allocate(size)).flatMap(f)
      }

    /** Performs a single channel read operation in to the supplied buffer. */
    protected def readChunk(buffer: ByteBuffer): F[Int]

    /** Performs a channel write operation(-s) from the supplied buffer.
      *
      * Write could be performed multiple times till all buffer remaining contents are written.
      */
    protected def writeChunk(buffer: ByteBuffer): F[Unit]

    def read(max: Int): F[Option[Chunk[Byte]]] =
      withReadBuffer(max) { buffer =>
        readChunk(buffer).map { read =>
          if (read < 0) None
          else if (buffer.position() == 0) Some(Chunk.empty)
          else {
            (buffer: Buffer).flip()
            Some(Chunk.byteBuffer(buffer.asReadOnlyBuffer()))
          }
        }
      }

    def readN(max: Int): F[Chunk[Byte]] =
      withReadBuffer(max) { buffer =>
        def go: F[Chunk[Byte]] =
          readChunk(buffer).flatMap { readBytes =>
            if (readBytes < 0 || buffer.position() >= max) {
              (buffer: Buffer).flip()
              F.pure(Chunk.byteBuffer(buffer.asReadOnlyBuffer()))
            } else go
          }
        go
      }

    def reads: Stream[F, Byte] =
      Stream.resource(lock(readMutex)).flatMap { _ =>
        Stream.unfoldChunkEval(ByteBuffer.allocate(maxReadChunkSize)) { case buffer =>
          readChunk(buffer).flatMap { read =>
            if (read < 0) none[(Chunk[Byte], ByteBuffer)].pure
            else if (buffer.position() == 0) {
              (Chunk.empty[Byte] -> buffer).some.pure
            } else if (buffer.remaining() == 0) {
              val bytes = buffer.asReadOnlyBuffer()
              val fresh = ByteBuffer.allocate(maxReadChunkSize)
              (bytes: Buffer).flip()
              (Chunk.byteBuffer(bytes) -> fresh).some.pure
            } else {
              val bytes = buffer.duplicate().asReadOnlyBuffer()
              val slice = buffer.slice()
              (bytes: Buffer).flip()
              (Chunk.byteBuffer(bytes) -> slice).some.pure
            }
          }
        }
      }

    def write(bytes: Chunk[Byte]): F[Unit] =
      lock(writeMutex).surround {
        F.delay(bytes.toByteBuffer).flatMap(writeChunk)
      }

    def writes: Pipe[F, Byte, Nothing] = { in =>
      Stream.resource(lock(writeMutex)).flatMap { _ =>
        in.chunks.foreach(bytes => writeChunk(bytes.toByteBuffer))
      }
    }
  }

  private final class AsyncSocket[F[_]](
      ch: AsynchronousSocketChannel,
      readMutex: Option[Mutex[F]],
      writeMutex: Option[Mutex[F]],
      maxReadChunkSize: Int
  )(implicit F: Async[F])
      extends BufferedReads[F](readMutex, writeMutex, maxReadChunkSize) {

    protected def readChunk(buffer: ByteBuffer): F[Int] =
      F.async[Int] { cb =>
        ch.read(
          buffer,
          null,
          new IntCompletionHandler(cb)
        )
        F.delay(Some(endOfInput.voidError))
      }

    protected def writeChunk(buffer: ByteBuffer): F[Unit] =
      F.async[Int] { cb =>
        ch.write(
          buffer,
          null,
          new IntCompletionHandler(cb)
        )
        F.delay(Some(endOfOutput.voidError))
      }.flatMap { written =>
        if (written < 0 || buffer.remaining() == 0) F.unit
        else writeChunk(buffer)
      }

    def localAddress: F[SocketAddress[IpAddress]] =
      F.delay(
        SocketAddress.fromInetSocketAddress(
          ch.getLocalAddress.asInstanceOf[InetSocketAddress]
        )
      )

    def remoteAddress: F[SocketAddress[IpAddress]] =
      F.delay(
        SocketAddress.fromInetSocketAddress(
          ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
        )
      )

    def isOpen: F[Boolean] = F.delay(ch.isOpen)

    def endOfOutput: F[Unit] =
      F.delay {
        ch.shutdownOutput(); ()
      }

    def endOfInput: F[Unit] =
      F.delay {
        ch.shutdownInput(); ()
      }
  }

  private final class IntCompletionHandler(cb: Either[Throwable, Int] => Unit)
      extends CompletionHandler[Integer, AnyRef] {
    def completed(i: Integer, attachment: AnyRef) =
      cb(Right(i))
    def failed(err: Throwable, attachment: AnyRef) =
      cb(Left(err))
  }
}
