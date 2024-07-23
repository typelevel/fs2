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

package fs2.io.net.unixsocket

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.io.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

private[unixsocket] trait UnixSocketsCompanionPlatform {
  def forIO: UnixSockets[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: UnixSockets[F] = {
    val _ = LiftIO[F]
    forAsyncAndFiles
  }

  def forAsyncAndFiles[F[_]: Async: Files]: UnixSockets[F] =
    if (JdkUnixSockets.supported) JdkUnixSockets.forAsyncAndFiles
    else if (JnrUnixSockets.supported) JnrUnixSockets.forAsyncAndFiles
    else
      throw new UnsupportedOperationException(
        """Must either run on JDK 16+ or have "com.github.jnr" % "jnr-unixsocket" % <version> on the classpath"""
      )

  def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    forAsyncAndFiles(F, Files.forAsync(F))

  private[unixsocket] abstract class AsyncUnixSockets[F[_]: Files](implicit F: Async[F])
      extends UnixSockets[F] {
    protected def openChannel(address: UnixSocketAddress): Resource[F, SocketChannel]
    protected def openServerChannel(
        address: UnixSocketAddress
    ): Resource[F, Resource[F, SocketChannel]]

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      openChannel(address).evalMap(makeSocket[F](_))

    def server(
        address: UnixSocketAddress,
        deleteIfExists: Boolean,
        deleteOnClose: Boolean
    ): Stream[F, Socket[F]] = {
      val delete = Resource.make {
        Files[F].deleteIfExists(Path(address.path)).whenA(deleteIfExists)
      } { _ =>
        Files[F].deleteIfExists(Path(address.path)).whenA(deleteOnClose)
      }

      Stream.resource(delete *> openServerChannel(address)).flatMap { accept =>
        Stream
          .resource(accept.attempt)
          .flatMap {
            case Left(_)         => Stream.empty[F]
            case Right(accepted) => Stream.eval(makeSocket(accepted))
          }
          .repeat
      }
    }
  }

  private def makeSocket[F[_]: Async](
      ch: SocketChannel
  ): F[Socket[F]] =
    makeSocket(ch, maxReadChunkSize = 16384, withExclusiveReads = true, withExclusiveWrites = true)

  private def makeSocket[F[_]](
      ch: SocketChannel,
      maxReadChunkSize: Int,
      withExclusiveReads: Boolean,
      withExclusiveWrites: Boolean
  )(implicit F: Async[F]): F[Socket[F]] = {
    def maybeMutex(maybe: Boolean) = F.defer(if (maybe) Mutex[F].map(Some(_)) else F.pure(None))
    (maybeMutex(withExclusiveReads), maybeMutex(withExclusiveWrites)).mapN {
      (readMutex, writeMutex) => new AsyncSocket[F](ch, readMutex, writeMutex, maxReadChunkSize)
    }
  }

  private final class AsyncSocket[F[_]](
      ch: SocketChannel,
      readMutex: Option[Mutex[F]],
      writeMutex: Option[Mutex[F]],
      maxReadChunkSize: Int
  )(implicit F: Async[F])
      extends Socket.BufferedReads[F](readMutex, writeMutex, maxReadChunkSize) {

    protected def readChunk(buffer: ByteBuffer): F[Int] =
      F.blocking(ch.read(buffer)).cancelable(close)

    protected def writeChunk(buffer: ByteBuffer): F[Unit] =
      F.blocking(ch.write(buffer)).cancelable(close).flatMap { _ =>
        if (buffer.remaining() == 0) F.unit
        else writeChunk(buffer)
      }

    def localAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError
    def remoteAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError
    private def raiseIpAddressError[A]: F[A] =
      F.raiseError(new UnsupportedOperationException("UnixSockets do not use IP addressing"))

    def isOpen: F[Boolean] = F.blocking(ch.isOpen())
    def close: F[Unit] = F.blocking(ch.close())
    def endOfOutput: F[Unit] =
      F.blocking {
        ch.shutdownOutput(); ()
      }
    def endOfInput: F[Unit] =
      F.blocking {
        ch.shutdownInput(); ()
      }
  }
}
