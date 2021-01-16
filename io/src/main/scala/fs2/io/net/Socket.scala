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
import cats.effect.std.Semaphore
import cats.syntax.all._

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousSocketChannel, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}

/** Provides the ability to read/write from a TCP socket in the effect `F`.
  */
trait Socket[F[_]] {

  /** Reads up to `maxBytes` from the peer.
    *
    * Returns `None` if no byte is read upon reaching the end of the stream.
    */
  def read(maxBytes: Int): F[Option[Chunk[Byte]]]

  /** Reads stream of bytes from this socket with `read` semantics. Terminates when eof is received.
    */
  def reads(maxBytes: Int): Stream[F, Byte]

  /** Reads exactly `numBytes` from the peer in a single chunk.
    *
    * When returned size of bytes is < `numBytes` that indicates end-of-stream has been reached.
    */
  def readN(numBytes: Int): F[Option[Chunk[Byte]]]

  /** Indicates that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: F[Unit]

  /** Indicates to peer, we are done writing. * */
  def endOfOutput: F[Unit]

  def isOpen: F[Boolean]

  /** Asks for the remote address of the peer. */
  def remoteAddress: F[SocketAddress[IpAddress]]

  /** Asks for the local address of the socket. */
  def localAddress: F[SocketAddress[IpAddress]]

  /** Writes `bytes` to the peer.
    *
    * Completes when the bytes are written to the socket.
    */
  def write(bytes: Chunk[Byte]): F[Unit]

  /** Writes the supplied stream of bytes to this socket via `write` semantics.
    */
  def writes: Pipe[F, Byte, INothing]
}

object Socket {
  private[net] def forAsync[F[_]: Async](
      ch: AsynchronousSocketChannel
  ): Resource[F, Socket[F]] =
    Resource.make {
      (Semaphore[F](1), Semaphore[F](1)).mapN { (readSemaphore, writeSemaphore) =>
        new AsyncSocket[F](ch, readSemaphore, writeSemaphore)
      }
    }(_ => Async[F].delay(if (ch.isOpen) ch.close else ()))

  private final class AsyncSocket[F[_]](
      ch: AsynchronousSocketChannel,
      readSemaphore: Semaphore[F],
      writeSemaphore: Semaphore[F]
  )(implicit F: Async[F])
      extends Socket[F] {
    private[this] var readBuffer: ByteBuffer = ByteBuffer.allocateDirect(8192)

    private def getBufferForRead(size: Int): F[ByteBuffer] = F.delay {
      if (readBuffer.capacity() < size)
        readBuffer = ByteBuffer.allocateDirect(size)
      else
        (readBuffer: Buffer).limit(size)
      readBuffer
    }

    /** Performs a single channel read operation in to the supplied buffer. */
    private def readChunk(buffer: ByteBuffer): F[Int] =
      F.async_[Int] { cb =>
        ch.read(
          buffer,
          null,
          new CompletionHandler[Integer, AnyRef] {
            def completed(result: Integer, attachment: AnyRef): Unit =
              cb(Right(result))
            def failed(err: Throwable, attachment: AnyRef): Unit =
              cb(Left(err))
          }
        )
      }

    /** Copies the contents of the supplied buffer to a `Chunk[Byte]` and clears the buffer contents. */
    private def releaseBuffer(buffer: ByteBuffer): F[Chunk[Byte]] =
      Async[F].delay {
        val read = buffer.position()
        val result =
          if (read == 0) Chunk.empty
          else {
            val dest = new Array[Byte](read)
            (buffer: Buffer).flip()
            buffer.get(dest)
            Chunk.array(dest)
          }
        (buffer: Buffer).clear()
        result
      }

    def read(max: Int): F[Option[Chunk[Byte]]] =
      readSemaphore.permit.use { _ =>
        getBufferForRead(max).flatMap { buffer =>
          readChunk(buffer).flatMap { read =>
            if (read < 0) Async[F].pure(None)
            else releaseBuffer(buffer).map(Some(_))
          }
        }
      }

    def reads(maxBytes: Int): Stream[F, Byte] =
      Stream.eval(read(maxBytes)).flatMap {
        case Some(bytes) =>
          Stream.chunk(bytes) ++ reads(maxBytes)
        case None => Stream.empty
      }

    def readN(max: Int): F[Option[Chunk[Byte]]] =
      readSemaphore.permit.use { _ =>
        getBufferForRead(max).flatMap { buffer =>
          def go: F[Option[Chunk[Byte]]] =
            readChunk(buffer).flatMap { readBytes =>
              if (readBytes < 0 || buffer.position() >= max)
                // read is done
                releaseBuffer(buffer).map(Some(_))
              else go
            }
          go
        }
      }

    def write(bytes: Chunk[Byte]): F[Unit] = {
      def go(buff: ByteBuffer): F[Unit] =
        Async[F]
          .async_[Unit] { cb =>
            ch.write(
              buff,
              (),
              new CompletionHandler[Integer, Unit] {
                def completed(result: Integer, attachment: Unit): Unit =
                  cb(Right(()))
                def failed(err: Throwable, attachment: Unit): Unit =
                  cb(Left(err))
              }
            )
          }
      writeSemaphore.permit.use { _ =>
        go(bytes.toByteBuffer)
      }
    }

    def writes: Pipe[F, Byte, INothing] =
      _.chunks.foreach(write)

    def localAddress: F[SocketAddress[IpAddress]] =
      Async[F].delay(
        SocketAddress.fromInetSocketAddress(
          ch.getLocalAddress.asInstanceOf[InetSocketAddress]
        )
      )
    def remoteAddress: F[SocketAddress[IpAddress]] =
      Async[F].delay(
        SocketAddress.fromInetSocketAddress(
          ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
        )
      )
    def isOpen: F[Boolean] = Async[F].delay(ch.isOpen)
    def close: F[Unit] = Async[F].delay(ch.close())
    def endOfOutput: F[Unit] =
      Async[F].delay {
        ch.shutdownOutput(); ()
      }
    def endOfInput: F[Unit] =
      Async[F].delay {
        ch.shutdownInput(); ()
      }
  }
}
