package fs2
package io
package tcp

import java.net.InetSocketAddress
import java.net.InetAddress

import cats.effect.IO
import cats.effect.concurrent.Deferred

class SocketSpec extends Fs2Spec {

  "tcp" - {

    // spawns echo server, takes whatever client sends and echoes it back
    // up to 10 clients concurrently (5k total) send message and awaits echo of it
    // success is that all clients got what they have sent
    "echo.requests" in {

      val message = Chunk.bytes("fs2.rocks".getBytes)
      val clientCount = 20

      val localBindAddress =
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val echoServer: SocketGroup => Stream[IO, Unit] = socketGroup =>
        socketGroup
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

      val clients: SocketGroup => Stream[IO, Array[Byte]] = socketGroup =>
        Stream
          .range(0, clientCount)
          .map { idx =>
            Stream.eval(localBindAddress.get).flatMap { local =>
              Stream.resource(socketGroup.client[IO](local)).flatMap { socket =>
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

      val result =
        Stream
          .resource(SocketGroup.simple[IO]())
          .flatMap { socketGroup =>
            Stream(echoServer(socketGroup).drain, clients(socketGroup))
              .parJoin(2)
              .take(clientCount)
          }
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

      val junkServer: SocketGroup => Stream[IO, Nothing] = socketGroup =>
        socketGroup
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

      val klient: SocketGroup => Stream[IO, Int] = socketGroup =>
        for {
          addr <- Stream.eval(localBindAddress.get)
          sock <- Stream.resource(socketGroup.client[IO](addr))
          size <- Stream.emits(sizes)
          op <- Stream.eval(sock.readN(size, None))
        } yield op.map(_.size).getOrElse(-1)

      val result =
        Stream
          .resource(SocketGroup.simple[IO]())
          .flatMap { socketGroup =>
            Stream(junkServer(socketGroup), klient(socketGroup))
              .parJoin(2)
              .take(sizes.length)
          }
          .compile
          .toVector
          .unsafeRunTimed(timeout)
          .get
      result shouldBe sizes

    }

  }

}
