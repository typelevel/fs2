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

import com.comcast.ip4s.{GenSocketAddress, IpAddress, SocketAddress}
import cats.effect.Async
import cats.effect.std.Mutex
import cats.syntax.all._

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousSocketChannel, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}

private[net] trait SocketCompanionPlatform {
  private[net] def forAsync[F[_]: Async](
      ch: AsynchronousSocketChannel
  ): F[Socket[F]] =
    (Mutex[F], Mutex[F]).mapN { (readMutex, writeMutex) =>
      new AsyncSocket[F](ch, readMutex, writeMutex)
    }

  private[net] abstract class BufferedReads[F[_]](
      readMutex: Mutex[F]
  )(implicit F: Async[F])
      extends Socket[F] {
    private[this] final val defaultReadSize = 8192
    private[this] var readBuffer: ByteBuffer = ByteBuffer.allocate(defaultReadSize)

    private def withReadBuffer[A](size: Int)(f: ByteBuffer => F[A]): F[A] =
      readMutex.lock.surround {
        F.delay {
          if (readBuffer.capacity() < size)
            readBuffer = ByteBuffer.allocate(size)
          else
            (readBuffer: Buffer).limit(size)
          f(readBuffer)
        }.flatten
      }

    /** Performs a single channel read operation in to the supplied buffer. */
    protected def readChunk(buffer: ByteBuffer): F[Int]

    /** Copies the contents of the supplied buffer to a `Chunk[Byte]` and clears the buffer contents. */
    private def releaseBuffer(buffer: ByteBuffer): F[Chunk[Byte]] =
      F.delay {
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
      withReadBuffer(max) { buffer =>
        readChunk(buffer).flatMap { read =>
          if (read < 0) F.pure(None)
          else releaseBuffer(buffer).map(Some(_))
        }
      }

    def readN(max: Int): F[Chunk[Byte]] =
      withReadBuffer(max) { buffer =>
        def go: F[Chunk[Byte]] =
          readChunk(buffer).flatMap { readBytes =>
            if (readBytes < 0 || buffer.position() >= max)
              releaseBuffer(buffer)
            else go
          }
        go
      }

    def reads: Stream[F, Byte] =
      Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

    def writes: Pipe[F, Byte, Nothing] =
      _.chunks.foreach(write)
  }

  private final class AsyncSocket[F[_]](
      ch: AsynchronousSocketChannel,
      readMutex: Mutex[F],
      writeMutex: Mutex[F]
  )(implicit F: Async[F])
      extends BufferedReads[F](readMutex)
      with SocketInfo.AsyncSocketInfo[F] {

    protected def asyncInstance = F
    protected def channel = ch

    protected def readChunk(buffer: ByteBuffer): F[Int] =
      F.async[Int] { cb =>
        ch.read(
          buffer,
          null,
          new IntCompletionHandler(cb)
        )
        F.delay(Some(endOfInput.voidError))
      }

    override def write(bytes: Chunk[Byte]): F[Unit] = {
      def go(buff: ByteBuffer): F[Unit] =
        F.async[Int] { cb =>
          ch.write(
            buff,
            null,
            new IntCompletionHandler(cb)
          )
          F.delay(Some(endOfOutput.voidError))
        }.flatMap { written =>
          if (written >= 0 && buff.remaining() > 0)
            go(buff)
          else F.unit
        }
      writeMutex.lock.surround {
        F.delay(bytes.toByteBuffer).flatMap(go)
      }
    }

    override def localAddress: F[SocketAddress[IpAddress]] =
      asyncInstance.delay(
        SocketAddress.fromInetSocketAddress(
          channel.getLocalAddress.asInstanceOf[InetSocketAddress]
        )
      )

    override def remoteAddress: F[SocketAddress[IpAddress]] =
      F.delay(
        SocketAddress.fromInetSocketAddress(
          ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
        )
      )

    override def remoteAddressGen: F[GenSocketAddress] =
      F.delay(SocketAddressHelpers.toGenSocketAddress(ch.getRemoteAddress))

    override def isOpen: F[Boolean] = F.delay(ch.isOpen)

    override def endOfOutput: F[Unit] =
      F.delay {
        ch.shutdownOutput(); ()
      }

    override def endOfInput: F[Unit] =
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
