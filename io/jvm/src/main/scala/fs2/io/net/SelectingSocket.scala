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
package io.net

import fs2.io.file.SyncFileHandle
import fs2.io.file.FileHandle
import cats.effect.LiftIO
import cats.effect.Selector
import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}

import java.nio.{Buffer, ByteBuffer}
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.nio.channels.SocketChannel

private final class SelectingSocket[F[_]: LiftIO] private (
    selector: Selector,
    ch: SocketChannel,
    readMutex: Mutex[F],
    writeMutex: Mutex[F],
    override val address: SocketAddress[IpAddress],
    val peerAddress: SocketAddress[IpAddress]
)(implicit F: Async[F])
    extends Socket[F]
    with SocketInfo.AsyncSocketInfo[F] {

  protected def asyncInstance = F
  protected def channel = ch

  override def localAddress: F[SocketAddress[IpAddress]] =
    asyncInstance.pure(address)

  override def remoteAddress: F[SocketAddress[IpAddress]] =
    asyncInstance.pure(peerAddress)

  /** Reads up to `max` bytes without waiting for the channel to become readable.
    *
    * The bytes are read in to the current thread's scratch buffer and copied to a new chunk, so
    * that sockets don't hold on to a read buffer between reads. Returns `None` at end-of-stream
    * and an empty chunk if no data is currently available.
    */
  private def readAvailable(max: Int): F[Option[Chunk[Byte]]] =
    F.delay {
      val buffer = SelectingSocket.scratchBuffer(max)
      val read = ch.read(buffer)
      if (read < 0) None
      else if (read == 0) Some(Chunk.empty)
      else {
        val dest = new Array[Byte](read)
        (buffer: Buffer).flip()
        buffer.get(dest)
        Some(Chunk.array(dest))
      }
    }

  private def awaitReadable: F[Unit] =
    selector.select(ch, OP_READ).void.to

  def read(max: Int): F[Option[Chunk[Byte]]] =
    readMutex.lock.surround {
      def go: F[Option[Chunk[Byte]]] =
        readAvailable(max).flatMap {
          case Some(bytes) if bytes.isEmpty && max > 0 => awaitReadable >> go
          case result                                  => F.pure(result)
        }
      go
    }

  /** Reads up to `max - filled` bytes in to `dest` at offset `filled`, without waiting for the
    * channel to become readable. `dest` is allocated with size `max` once the first bytes arrive,
    * so that each byte is copied only once, out of the scratch buffer, and so that a socket waiting
    * for its first bytes doesn't hold on to an array.
    *
    * Returns the (possibly newly allocated) destination and the number of bytes read, which is
    * negative at end-of-stream.
    */
  private def readAvailableInto(
      dest: Array[Byte],
      filled: Int,
      max: Int
  ): F[(Array[Byte], Int)] =
    F.delay {
      val buffer = SelectingSocket.scratchBuffer(max - filled)
      val read = ch.read(buffer)
      if (read <= 0) (dest, read)
      else {
        val target = if (dest.length == 0) new Array[Byte](max) else dest
        (buffer: Buffer).flip()
        buffer.get(target, filled, read)
        (target, read)
      }
    }

  def readN(max: Int): F[Chunk[Byte]] =
    readMutex.lock.surround {
      def go(dest: Array[Byte], filled: Int): F[Chunk[Byte]] =
        if (filled >= max) F.pure(Chunk.array(dest))
        else
          readAvailableInto(dest, filled, max).flatMap {
            case (next, read) if read < 0 =>
              // end-of-stream before `max` bytes; don't retain the unused part of `dest`
              F.pure(Chunk.array(java.util.Arrays.copyOf(next, filled)))
            case (next, 0)    => awaitReadable >> go(next, filled)
            case (next, read) => go(next, filled + read)
          }
      if (max <= 0) F.pure(Chunk.empty) else go(Array.emptyByteArray, 0)
    }

  def reads: Stream[F, Byte] =
    Stream.repeatEval(read(SelectingSocket.DefaultReadSize)).unNoneTerminate.unchunks

  def writes: Pipe[F, Byte, Nothing] =
    _.chunks.foreach(write)

  def write(bytes: Chunk[Byte]): F[Unit] = {
    def go(buf: ByteBuffer): F[Unit] =
      F.delay {
        ch.write(buf)
        buf.remaining()
      }.flatMap { remaining =>
        if (remaining > 0) {
          selector.select(ch, OP_WRITE).to *> go(buf)
        } else F.unit
      }
    writeMutex.lock.surround {
      F.delay(bytes.toByteBuffer).flatMap(go)
    }
  }

  def isOpen: F[Boolean] = F.delay(ch.isOpen)

  def endOfOutput: F[Unit] =
    F.delay {
      ch.shutdownOutput(); ()
    }

  def endOfInput: F[Unit] =
    F.delay {
      ch.shutdownInput(); ()
    }

  override def sendFile(
      file: FileHandle[F],
      offset: Long,
      count: Long,
      chunkSize: Int
  ): Stream[F, Nothing] = file match {
    case syncFileHandle: SyncFileHandle[F] =>
      val fileChannel = syncFileHandle.chan

      def go(currOffset: Long, remaining: Long): F[Unit] =
        if (remaining <= 0) F.unit
        else {
          F.blocking(fileChannel.transferTo(currOffset, remaining, ch)).flatMap { written =>
            if (written > 0) {
              go(currOffset + written, remaining - written)
            } else {
              selector.select(ch, OP_WRITE).to *> go(currOffset, remaining)
            }
          }
        }

      Stream.exec(writeMutex.lock.surround(go(offset, count)))

    case _ =>
      super.sendFile(file, offset, count, chunkSize)
  }
}

private object SelectingSocket {
  private final val DefaultReadSize = 8192

  /** Upper bound on the size of a scratch buffer. Each thread keeps its buffer for as long as it
    * lives, so a single large read must not leave a large buffer behind.
    */
  private final val MaxScratchSize = 64 * 1024

  private[this] val scratchBuffers = new ThreadLocal[ByteBuffer]

  /** Returns the current thread's scratch buffer, cleared and with its limit set to `size`, capped
    * at `MaxScratchSize`. A single read may therefore return fewer than `size` bytes, which callers
    * already allow for.
    *
    * The buffer is only used within a single synchronous read and its contents are copied out
    * before returning, so it never escapes the reading thread. It is a direct buffer, so the
    * channel reads in to it without going through the JDK's own per-thread temporary buffer.
    */
  private def scratchBuffer(size: Int): ByteBuffer = {
    val limit = math.min(size, MaxScratchSize)
    var buffer = scratchBuffers.get()
    if (buffer == null || buffer.capacity() < limit) {
      buffer = ByteBuffer.allocateDirect(math.max(limit, DefaultReadSize))
      scratchBuffers.set(buffer)
    }
    (buffer: Buffer).clear()
    (buffer: Buffer).limit(limit)
    buffer
  }

  def apply[F[_]: LiftIO](
      selector: Selector,
      ch: SocketChannel,
      address: SocketAddress[IpAddress],
      remoteAddress: SocketAddress[IpAddress]
  )(implicit F: Async[F]): F[Socket[F]] =
    (Mutex[F], Mutex[F]).flatMapN { (readMutex, writeMutex) =>
      F.delay {
        new SelectingSocket[F](
          selector,
          ch,
          readMutex,
          writeMutex,
          address,
          remoteAddress
        )
      }
    }
}
