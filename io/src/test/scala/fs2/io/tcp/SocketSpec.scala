package fs2.io.tcp

import java.net.InetSocketAddress
import java.net.InetAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider

import cats.effect.IO

import fs2._
import fs2.internal.ThreadFactories

import org.scalatest.BeforeAndAfterAll

import TestUtil._

class SocketSpec extends Fs2Spec with BeforeAndAfterAll {

  implicit val tcpACG: AsynchronousChannelGroup = AsynchronousChannelProvider
    .provider()
    .openAsynchronousChannelGroup(8, ThreadFactories.named("fs2-ag-tcp", true))

  override def afterAll() = {
    tcpACG.shutdownNow
    super.afterAll()
  }

  "tcp" - {

    // spawns echo server, takes whatever client sends and echoes it back
    // up to 10 clients concurrently (5k total) send message and awaits echo of it
    // success is that all clients got what they have sent
    "echo.requests" in {

      val message = Chunk.bytes("fs2.rocks".getBytes)
      val clientCount = 20

      val localBindAddress =
        async.promise[IO, InetSocketAddress].unsafeRunSync()

      val echoServer: Stream[IO, Unit] = {
        serverWithLocalAddress[IO](new InetSocketAddress(InetAddress.getByName(null), 0)).flatMap {
          case Left(local) => Stream.eval_(localBindAddress.complete(local))
          case Right(s) =>
            s.map { socket =>
              socket
                .reads(1024)
                .to(socket.writes())
                .onFinalize(socket.endOfOutput)
            }
        }.joinUnbounded
      }

      val clients: Stream[IO, Array[Byte]] = {
        Stream
          .range(0, clientCount)
          .covary[IO]
          .map { idx =>
            Stream.eval(localBindAddress.get).flatMap { local =>
              client[IO](local).flatMap { socket =>
                Stream
                  .chunk(message)
                  .covary[IO]
                  .to(socket.writes())
                  .drain
                  .onFinalize(socket.endOfOutput) ++
                  socket.reads(1024, None).chunks.map(_.toArray)
              }
            }
          }
          .join(10)
      }

      val result = Stream(echoServer.drain, clients)
        .join(2)
        .take(clientCount)
        .compile
        .toVector
        .unsafeRunTimed(timeout)
        .get
      result.size shouldBe clientCount
      result.map { new String(_) }.toSet shouldBe Set("fs2.rocks")
    }

    // Ensure that readN yields chunks of the requested size
    "readN" in {

      val message = Chunk.bytes("123456789012345678901234567890".getBytes)

      val localBindAddress =
        async.promise[IO, InetSocketAddress].unsafeRunSync()

      val junkServer: Stream[IO, Nothing] =
        serverWithLocalAddress[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          .flatMap {
            case Left(local) => Stream.eval_(localBindAddress.complete(local))
            case Right(s) =>
              Stream.emit(s.flatMap { socket =>
                Stream
                  .chunk(message)
                  .covary[IO]
                  .to(socket.writes())
                  .drain
                  .onFinalize(socket.endOfOutput)
              })
          }
          .joinUnbounded
          .drain

      val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

      val klient: Stream[IO, Int] =
        for {
          addr <- Stream.eval(localBindAddress.get)
          sock <- client[IO](addr)
          size <- Stream.emits(sizes).covary[IO]
          op <- Stream.eval(sock.readN(size, None))
        } yield op.map(_.size).getOrElse(-1)

      val result =
        Stream(junkServer, klient)
          .join(2)
          .take(sizes.length)
          .compile
          .toVector
          .unsafeRunTimed(timeout)
          .get
      result shouldBe sizes

    }

  }

}
