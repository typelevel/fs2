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
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousSocketChannel, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}

private[net] trait SocketCompanionPlatform {
  private[net] def forAsync[F[_]: Async](
      ch: AsynchronousSocketChannel
  ): Resource[F, Socket[F]] =
    Resource.make {
      (Semaphore[F](1), Ref[F].of(Chunk.empty[Byte]), Semaphore[F](1), Semaphore[F](1)).mapN {
        (readBufferSemaphore, readBuffer, channelReadSemaphore, writeSemaphore) =>
          new AsyncSocket[F](
            ch,
            readBufferSemaphore,
            readBuffer,
            channelReadSemaphore,
            writeSemaphore
          )
      }
    }(_ => Async[F].delay(if (ch.isOpen) ch.close else ()))

  private[net] abstract class BufferedReads[F[_]](
      readBufferSemaphore: Semaphore[F],
      readBuffer: Ref[F, Chunk[Byte]]
  )(implicit
      F: Async[F]
  ) extends Socket[F] {
    private[this] final val defaultReadSize = 8192

    private def cutInitialSegmentOfBuffer(size: Int): F[Chunk[Byte]] =
      readBuffer.modify(_.splitAt(size).swap)

    private def getBufferContentSize: F[Int] =
      readBuffer.get.map(_.size)

    protected final def storeToReadBuffer(filledBuffer: ByteBuffer): F[Unit] =
      F.delay {
        val read = filledBuffer.position()

        if (read == 0) Chunk.empty
        else {
          val dest = ByteBuffer.allocateDirect(read)
          (filledBuffer: Buffer).flip()
          dest.put(filledBuffer)
          (dest: Buffer).flip()
          Chunk.byteBuffer(dest)
        }
      }.flatMap(chunk => readBuffer.update(_ ++ chunk))

    /** Performs a single, mutually-exclusive channel read operation that reads
      * maximum of `max` bytes. The read result is stored to the buffer
      * via [[storeToReadBuffer]].
      *
      * This action itself may be cancellable, but even in the event of cancellation
      * the underlying read operation, together with the action to store the read result,
      * are not cancelled.
      *
      * The effect returns a number of bytes read, or a negative number if no more bytes
      * can be read from the channel.
      */
    protected def readChunk(maxBytes: Int): F[Int]

    def read(max: Int): F[Option[Chunk[Byte]]] =
      readBufferSemaphore.permit.use { _ =>
        getBufferContentSize.flatMap { contentLength =>
          readChunk(max - contentLength).flatMap { read =>
            if (contentLength == 0 && read < 0)
              F.pure(None)
            else
              F.uncancelable { _ =>
                cutInitialSegmentOfBuffer(max).map(Some.apply)
              }
          }
        }
      }

    def readN(max: Int): F[Chunk[Byte]] =
      readBufferSemaphore.permit.use { _ =>
        // readChunk(0) ensures that no read operation is pending
        // this is necessary to ensure that latestAvailable is synced throughout
        // the tail-recursion below
        readChunk(0) >> getBufferContentSize.flatMap { contentLength =>
          def go(latestAvailable: Int): F[Chunk[Byte]] = {
            val toRead = max - latestAvailable
            readChunk(toRead).flatMap { read =>
              if (read < 0 || toRead == read)
                cutInitialSegmentOfBuffer(max)
              else {
                go(latestAvailable + read)
              }
            }
          }

          go(contentLength)
        }
      }

    def reads: Stream[F, Byte] =
      Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

    def writes: Pipe[F, Byte, INothing] =
      _.chunks.foreach(write)
  }

  private final class AsyncSocket[F[_]](
      ch: AsynchronousSocketChannel,
      bufferSemaphore: Semaphore[F],
      buffer: Ref[F, Chunk[Byte]],
      channelReadSemaphore: Semaphore[F],
      writeSemaphore: Semaphore[F]
  )(implicit F: Async[F])
      extends BufferedReads[F](bufferSemaphore, buffer) {
    override protected def readChunk(maxBytes: Int): F[Int] =
      F.start(
        channelReadSemaphore.permit.use(_ =>
          F.delay(ByteBuffer.allocateDirect(maxBytes)).flatMap { buffer =>
            F.async_[Int](cb => ch.read(buffer, null, new IntCallbackHandler(cb))).flatTap { _ =>
              storeToReadBuffer(buffer)
            }
          }
        )
      ).flatMap(_.join)
        .flatMap(_.embedNever /* the child fiber is never cancelled */ )

    def write(bytes: Chunk[Byte]): F[Unit] = {
      def go(buff: ByteBuffer): F[Unit] =
        F.async_[Int] { cb =>
          ch.write(
            buff,
            null,
            new IntCallbackHandler(cb)
          )
        }.flatMap { written =>
          if (written >= 0 && buff.remaining() > 0)
            go(buff)
          else F.unit
        }
      writeSemaphore.permit.use { _ =>
        go(bytes.toByteBuffer)
      }
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

  private final class IntCallbackHandler[A](cb: Either[Throwable, Int] => Unit)
      extends CompletionHandler[Integer, AnyRef] {
    def completed(result: Integer, attachment: AnyRef): Unit =
      cb(Right(result))
    def failed(err: Throwable, attachment: AnyRef): Unit =
      cb(Left(err))
  }
}
