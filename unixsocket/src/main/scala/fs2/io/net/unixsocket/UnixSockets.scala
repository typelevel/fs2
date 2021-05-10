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

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}
import fs2.{Chunk, Stream}
import fs2.io.file.Files
import fs2.io.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Paths

/** Capability of working with AF_UNIX sockets. */
trait UnixSockets[F[_]] {

  /** Returns a resource which opens a unix socket to the specified path.
    */
  def client(address: UnixSocketAddress): Resource[F, Socket[F]]

  /** Listens to the specified path for connections and emits a `Socket` for each connection.
    *
    * Note: the path referred to by `address` must not exist or otherwise binding will fail
    * indicating the address is already in use. To force binding in such a case, pass `deleteIfExists = true`,
    * which will first delete the path.
    *
    * By default, the path is deleted when the server closes. To override this, pass `deleteOnClose = false`.
    */
  def server(
      address: UnixSocketAddress,
      deleteIfExists: Boolean = false,
      deleteOnClose: Boolean = true
  ): Stream[F, Socket[F]]
}

object UnixSockets {
  def apply[F[_]](implicit F: UnixSockets[F]): UnixSockets[F] = F

  implicit def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    if (JdkUnixSockets.supported) JdkUnixSockets.forAsync
    else if (JnrUnixSockets.supported) JnrUnixSockets.forAsync
    else
      throw new UnsupportedOperationException(
        """Must either run on JDK 16+ or have "com.github.jnr" % "jnr-unixsocket" % <version> on the classpath"""
      )

  private[unixsocket] abstract class AsyncUnixSockets[F[_]](implicit F: Async[F])
      extends UnixSockets[F] {
    protected def openChannel(address: UnixSocketAddress): F[SocketChannel]
    protected def openServerChannel(address: UnixSocketAddress): F[(F[SocketChannel], F[Unit])]

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      Resource
        .eval(openChannel(address))
        .flatMap(makeSocket[F](_))

    def server(
        address: UnixSocketAddress,
        deleteIfExists: Boolean,
        deleteOnClose: Boolean
    ): Stream[F, Socket[F]] = {
      def setup =
        Files[F].deleteIfExists(Paths.get(address.path)).whenA(deleteIfExists) *>
          openServerChannel(address)

      def cleanup(closeChannel: F[Unit]): F[Unit] =
        closeChannel *>
          Files[F].deleteIfExists(Paths.get(address.path)).whenA(deleteOnClose)

      def acceptIncoming(accept: F[SocketChannel]): Stream[F, Socket[F]] = {
        def go: Stream[F, Socket[F]] = {
          def acceptChannel: F[SocketChannel] =
            accept.map { ch =>
              ch.configureBlocking(false)
              ch
            }

          Stream.eval(acceptChannel.attempt).flatMap {
            case Left(_)         => Stream.empty[F]
            case Right(accepted) => Stream.resource(makeSocket(accepted))
          } ++ go
        }
        go
      }

      Stream
        .resource(Resource.make(setup) { case (_, closeChannel) => cleanup(closeChannel) })
        .flatMap { case (accept, _) => acceptIncoming(accept) }
    }
  }

  private def makeSocket[F[_]: Async](
      ch: SocketChannel
  ): Resource[F, Socket[F]] =
    Resource.make {
      (Semaphore[F](1), Semaphore[F](1)).mapN { (readSemaphore, writeSemaphore) =>
        new AsyncSocket[F](ch, readSemaphore, writeSemaphore)
      }
    }(_ => Async[F].delay(if (ch.isOpen) ch.close else ()))

  private final class AsyncSocket[F[_]](
      ch: SocketChannel,
      readSemaphore: Semaphore[F],
      writeSemaphore: Semaphore[F]
  )(implicit F: Async[F])
      extends Socket.BufferedReads[F](readSemaphore) {

    def readChunk(buff: ByteBuffer): F[Int] =
      F.blocking(ch.read(buff))

    def write(bytes: Chunk[Byte]): F[Unit] = {
      def go(buff: ByteBuffer): F[Unit] =
        F.blocking(ch.write(buff)) >> {
          if (buff.remaining <= 0) F.unit
          else go(buff)
        }
      writeSemaphore.permit.use { _ =>
        go(bytes.toByteBuffer)
      }
    }

    def localAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError
    def remoteAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError
    private def raiseIpAddressError[A]: F[A] =
      F.raiseError(new UnsupportedOperationException("UnixSockets do not use IP addressing"))

    def isOpen: F[Boolean] = F.blocking(ch.isOpen)
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
