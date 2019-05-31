package fs2
package io
package tcp

import java.net.InetSocketAddress
import java.net.InetAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider

import cats.effect.IO
import cats.effect.concurrent.Deferred

import fs2.internal.ThreadFactories

import org.scalatest.BeforeAndAfterAll

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
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val echoServer: Stream[IO, Unit] = {
        Socket
          .serverWithLocalAddress[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          .flatMap {
            case Left(local) => Stream.eval_(localBindAddress.complete(local))
            case Right(s) =>
              Stream.resource(s).map { socket =>
                socket
                  .reads(1024)
                  .through(socket.writes())
                  .onFinalize(socket.endOfOutput)
                  .scope
              }
          }
          .parJoinUnbounded
      }

      val clients: Stream[IO, Array[Byte]] = {
        Stream
          .range(0, clientCount)
          .map { idx =>
            Stream.eval(localBindAddress.get).flatMap { local =>
              Stream.resource(Socket.client[IO](local)).flatMap { socket =>
                Stream
                  .chunk(message)
                  .through(socket.writes())
                  .drain
                  .onFinalize(socket.endOfOutput)
                  .scope ++
                  socket.reads(1024, None).chunks.map(_.toArray)
              }
            }
          }
          .parJoin(10)
      }

      val result = Stream(echoServer.drain, clients)
        .parJoin(2)
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
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val junkServer: Stream[IO, Nothing] =
        Socket
          .serverWithLocalAddress[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          .flatMap {
            case Left(local) => Stream.eval_(localBindAddress.complete(local))
            case Right(s) =>
              Stream.emit(Stream.resource(s).flatMap { socket =>
                Stream
                  .chunk(message)
                  .through(socket.writes())
                  .drain
                  .onFinalize(socket.endOfOutput)
                  .scope
              })
          }
          .parJoinUnbounded
          .drain

      val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

      val klient: Stream[IO, Int] =
        for {
          addr <- Stream.eval(localBindAddress.get)
          sock <- Stream.resource(Socket.client[IO](addr))
          size <- Stream.emits(sizes)
          op <- Stream.eval(sock.readN(size, None))
        } yield op.map(_.size).getOrElse(-1)

      val result =
        Stream(junkServer, klient)
          .parJoin(2)
          .take(sizes.length)
          .compile
          .toVector
          .unsafeRunTimed(timeout)
          .get
      result shouldBe sizes

    }

  }

}
