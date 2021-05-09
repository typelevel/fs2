package fs2.io.net.unixsocket

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}
import fs2.{Chunk, Stream}
import fs2.io.file.Files
import fs2.io.net.Socket
import java.nio.ByteBuffer
import java.nio.file.{Path, Paths}
import jnr.unixsocket.UnixServerSocketChannel
import jnr.unixsocket.impl.AbstractNativeSocketChannel

/** Capability of workings with AF_UNIX sockets. */
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
  def server(address: UnixSocketAddress, deleteIfExists: Boolean = false, deleteOnClose: Boolean = true): Stream[F, Socket[F]]
}

object UnixSockets {

  def apply[F[_]](implicit F: UnixSockets[F]): UnixSockets[F] = F

  implicit def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] = new UnixSockets[F] {

    import jnr.unixsocket.UnixSocketChannel

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      Resource
        .eval(F.delay(UnixSocketChannel.open(address.toJnr)))
        .flatMap(makeSocket[F](_))

    def server(address: UnixSocketAddress, deleteIfExists: Boolean, deleteOnClose: Boolean): Stream[F, Socket[F]] = {
      def setup =
        Files[F].deleteIfExists(Paths.get(address.path)).whenA(deleteIfExists) *>
          F.blocking {
            val serverChannel = UnixServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            val sock = serverChannel.socket()
            sock.bind(address.toJnr)
            serverChannel
          }

      def cleanup(sch: UnixServerSocketChannel): F[Unit] =
        F.blocking(sch.close()) *>
          Files[F].deleteIfExists(Paths.get(address.path)).whenA(deleteOnClose)

      def acceptIncoming(sch: UnixServerSocketChannel): Stream[F, Socket[F]] = {
        def go: Stream[F, Socket[F]] = {
          def acceptChannel: F[UnixSocketChannel] =
            F.blocking {
              val ch = sch.accept()
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

      Stream.resource(Resource.make(setup)(cleanup)).flatMap(acceptIncoming)
    }
  }

  private def makeSocket[F[_]: Async](
      ch: AbstractNativeSocketChannel
  ): Resource[F, Socket[F]] =
    Resource.make {
      (Semaphore[F](1), Semaphore[F](1)).mapN { (readSemaphore, writeSemaphore) =>
        new AsyncSocket[F](ch, readSemaphore, writeSemaphore)
      }
    }(_ => Async[F].delay(if (ch.isOpen) ch.close else ()))

  private final class AsyncSocket[F[_]](
      ch: AbstractNativeSocketChannel,
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
