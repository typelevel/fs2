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
package tcp

import scala.concurrent.duration._

import java.net.InetSocketAddress
import java.net.InetAddress

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.Resource

class SocketSuite extends Fs2Suite {
  def mkSocketGroup: Stream[IO, SocketGroup] =
    Stream.resource(SocketGroup[IO]())

  val timeout = 30.seconds

  group("tcp") {
    // spawns echo server, takes whatever client sends and echoes it back
    // up to 10 clients concurrently (5k total) send message and awaits echo of it
    // success is that all clients got what they have sent
    test("echo.requests") {
      val message = Chunk.bytes("fs2.rocks".getBytes)
      val clientCount = 20

      val localBindAddress =
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val echoServer: SocketGroup => Stream[IO, Unit] = socketGroup =>
        Stream
          .resource(
            socketGroup
              .serverResource[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          )
          .flatMap { case (local, clients) =>
            Stream.exec(localBindAddress.complete(local).void) ++
              clients.flatMap { s =>
                Stream.resource(s).map { socket =>
                  socket
                    .reads(1024)
                    .through(socket.writes())
                    .onFinalize(socket.endOfOutput)
                }
              }
          }
          .parJoinUnbounded

      val clients: SocketGroup => Stream[IO, Array[Byte]] = socketGroup =>
        Stream
          .range(0, clientCount)
          .map { _ =>
            Stream.eval(localBindAddress.get).flatMap { local =>
              Stream.resource(socketGroup.client[IO](local)).flatMap { socket =>
                Stream
                  .chunk(message)
                  .through(socket.writes())
                  .onFinalize(socket.endOfOutput) ++
                  socket.reads(1024, None).chunks.map(_.toArray)
              }
            }
          }
          .parJoin(10)

      val result =
        mkSocketGroup
          .flatMap { socketGroup =>
            Stream(echoServer(socketGroup).drain, clients(socketGroup))
              .parJoin(2)
              .take(clientCount.toLong)
          }
          .compile
          .toVector
          .unsafeRunTimed(timeout)
          .get
      assert(result.size == clientCount)
      assert(result.map(new String(_)).toSet == Set("fs2.rocks"))
    }

    // Ensure that readN yields chunks of the requested size
    test("readN") {
      val message = Chunk.bytes("123456789012345678901234567890".getBytes)

      val localBindAddress =
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val junkServer: SocketGroup => Stream[IO, Nothing] = socketGroup =>
        Stream
          .resource(
            socketGroup
              .serverResource[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          )
          .flatMap { case (local, clients) =>
            Stream.exec(localBindAddress.complete(local).void) ++
              clients.flatMap { s =>
                Stream.emit(Stream.resource(s).flatMap { socket =>
                  Stream
                    .chunk(message)
                    .through(socket.writes())
                    .onFinalize(socket.endOfOutput)
                })
              }
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
        mkSocketGroup
          .flatMap { socketGroup =>
            Stream(junkServer(socketGroup), klient(socketGroup))
              .parJoin(2)
              .take(sizes.length.toLong)
          }
          .compile
          .toVector
          .unsafeRunTimed(timeout)
          .get
      assert(result == sizes)
    }

    test("write - concurrent calls do not cause WritePendingException") {
      val message = Chunk.bytes(("123456789012345678901234567890" * 10000).getBytes)

      val localBindAddress =
        Deferred[IO, InetSocketAddress].unsafeRunSync()

      val server: SocketGroup => Stream[IO, Unit] = socketGroup =>
        Stream
          .resource(
            socketGroup
              .serverResource[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
          )
          .flatMap { case (local, clients) =>
            Stream.exec(localBindAddress.complete(local).void) ++
              clients.flatMap { s =>
                Stream.resource(s).map { socket =>
                  socket.reads(1024).drain
                }
              }
          }
          .parJoinUnbounded

      mkSocketGroup
        .flatMap { socketGroup =>
          Stream
            .eval(localBindAddress.get)
            .flatMap { address =>
              Stream
                .resource(
                  socketGroup.client[IO](address)
                )
                .flatMap(sock =>
                  Stream(
                    Stream.eval(sock.write(message)).repeatN(10L)
                  ).repeatN(2L)
                )
                .parJoinUnbounded
            }
            .concurrently(server(socketGroup))
        }
        .compile
        .drain
        .attempt
        .map(it => assert(it.isRight))
    }
  }
}
