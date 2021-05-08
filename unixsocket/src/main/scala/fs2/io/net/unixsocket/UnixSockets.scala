package fs2.io.net.unixsocket

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import fs2.{Chunk, Stream}
import fs2.io.file.Files
import fs2.io.net.Socket
import java.nio.ByteBuffer
import java.nio.file.{Path, Paths}
import jnr.unixsocket.UnixServerSocketChannel
import jnr.unixsocket.impl.AbstractNativeSocketChannel

trait UnixSockets[F[_]] {

  def client(address: UnixSocketAddress): Resource[F, Socket[F]]

  def server(address: UnixSocketAddress): Stream[F, Socket[F]]

}

object UnixSockets {

  def apply[F[_]](implicit F: UnixSockets[F]): UnixSockets[F] = F

  implicit def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] = new UnixSockets[F] {

    import jnr.unixsocket.UnixSocketChannel

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      Resource
        .eval(F.delay(UnixSocketChannel.open(address.toJnr)))
        .flatMap(makeSocket[F](_))

    def server(address: UnixSocketAddress): Stream[F, Socket[F]] = {
      def setup = Files[F]
        .exists(Paths.get(address.path))
        .ifM(
          F.raiseError(
            new RuntimeException(
              "Socket Location Already Exists, Server cannot create Socket Location when location already exists."
            )
          ),
          F.blocking {
            val serverChannel = UnixServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            val sock = serverChannel.socket()
            sock.bind(address.toJnr)
            serverChannel
          }
        )

      def cleanup(sch: UnixServerSocketChannel): F[Unit] =
        F.blocking {
          if (sch.isOpen) sch.close()
          if (sch.isRegistered()) println("Server Still Registered")
        }

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

    // TODO move
    import com.comcast.ip4s.{IpAddress => IpAddress4s, SocketAddress => SocketAddress4s}
    import java.net.InetSocketAddress
    def localAddress: F[SocketAddress4s[IpAddress4s]] =
      F.blocking {
        // TODO this is always? null
        SocketAddress4s.fromInetSocketAddress(
          ch.getLocalAddress.asInstanceOf[InetSocketAddress]
        )
      }
    def remoteAddress: F[SocketAddress4s[IpAddress4s]] =
      F.blocking {
        // TODO this throws class cast exception
        SocketAddress4s.fromInetSocketAddress(
          ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
        )
      }

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
