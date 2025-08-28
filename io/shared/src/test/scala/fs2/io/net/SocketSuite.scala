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

import cats.effect.IO
import com.comcast.ip4s.{UnknownHostException => Ip4sUnknownHostException, _}

import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import fs2.io.file._

class SocketSuite extends Fs2Suite with SocketSuitePlatform {

  val timeout = 30.seconds

  val setup = for {
    serverSocket <- Network[IO].bind(address = SocketAddress(ip"127.0.0.1", Port.Wildcard))
    clients =
      Stream
        .resource(
          Network[IO].connect(serverSocket.address, options = setupOptionsPlatform)
        )
        .repeat
  } yield serverSocket.accept -> clients

  group("tcp") {
    test("echo requests - each concurrent client gets back what it sent") {
      val message = Chunk.array("fs2.rocks".getBytes)
      val clientCount = 20L

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val echoServer = server.map { socket =>
            socket.reads
              .through(socket.writes)
              .onFinalize(socket.endOfOutput)
          }.parJoinUnbounded

          val msgClients = clients
            .take(clientCount)
            .map { socket =>
              Stream
                .chunk(message)
                .through(socket.writes)
                .onFinalize(socket.endOfOutput) ++
                socket.reads.chunks
                  .map(bytes => new String(bytes.toArray))
            }
            .parJoin(10)
            .take(clientCount)

          msgClients.concurrently(echoServer)
        }
        .compile
        .toVector
        .map { it =>
          assertEquals(it.size.toLong, clientCount)
          assert(it.forall(_ == "fs2.rocks"))
        }
    }

    test("readN yields chunks of the requested size") {
      val message = Chunk.array("123456789012345678901234567890".getBytes)
      val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val junkServer = server.map { socket =>
            Stream
              .chunk(message)
              .through(socket.writes)
              .onFinalize(socket.endOfOutput)
          }.parJoinUnbounded

          val client =
            clients
              .take(1)
              .flatMap { socket =>
                Stream
                  .emits(sizes)
                  .evalMap(socket.readN(_))
                  .map(_.size)
              }
              .take(sizes.length.toLong)

          client.concurrently(junkServer)
        }
        .compile
        .toVector
        .assertEquals(sizes)
    }

    test("write - concurrent calls do not cause a WritePendingException") {
      val message = Chunk.array(("123456789012345678901234567890" * 10000).getBytes)

      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val readOnlyServer = server.map(_.reads).parJoinUnbounded
          val client =
            clients.take(1).flatMap { socket =>
              // concurrent writes
              Stream {
                Stream.eval(socket.write(message)).repeatN(10L)
              }.repeatN(2L).parJoinUnbounded
            }

          client.concurrently(readOnlyServer)
        }
        .compile
        .drain
    }

    test("addresses - should match across client and server sockets") {
      Stream
        .resource(setup)
        .flatMap { case (server, clients) =>
          val serverSocketAddresses = server.evalMap { socket =>
            socket.endOfOutput.as(socket.address -> socket.peerAddress)
          }

          val clientSocketAddresses =
            clients
              .take(1)
              .evalMap { socket =>
                socket.endOfOutput.as(socket.address -> socket.peerAddress)
              }

          serverSocketAddresses.parZip(clientSocketAddresses).map {
            case ((serverLocal, serverRemote), (clientLocal, clientRemote)) =>
              assertEquals(clientRemote, serverLocal)
              assertEquals(clientLocal, serverRemote)
          }
        }
        .compile
        .drain
    }

    test("errors - should be captured in the effect") {
      val connectionRefused = for {
        port <- Network[IO]
          .bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
          .use(serverSocket => IO.pure(serverSocket.address.asIpUnsafe.port))
        _ <- Network[IO]
          .connect(SocketAddress(host"localhost", port))
          .use_
          .interceptMessage[ConnectException]("Connection refused")
      } yield ()

      val addressAlreadyInUse =
        Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard)).use { serverSocket =>
          Network[IO]
            .bind(serverSocket.address)
            .use_
            .interceptMessage[BindException]("Address already in use")
        }

      val unknownHost = Network[IO]
        .connect(SocketAddress.fromString("not.example.com:80").get)
        .use_
        .attempt
        .map {
          case Left(ex: Ip4sUnknownHostException) =>
            assert(
              ex.getMessage == "not.example.com: Name or service not known" || ex.getMessage == "not.example.com: nodename nor servname provided, or not known"
            )
          case _ => assert(false)
        }

      connectionRefused *> addressAlreadyInUse *> unknownHost
    }

    test("options - should work with socket options") {
      val opts = List(
        SocketOption.keepAlive(true),
        SocketOption.noDelay(true)
      ) ++ optionsPlatform
      val setup = for {
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard), opts)
        client <- Network[IO].connect(serverSocket.address, opts)
      } yield (serverSocket.accept, client)

      val msg = "hello"

      Stream
        .resource(setup)
        .flatMap { case (server, client) =>
          val echoServer = server.map { socket =>
            socket.reads.through(socket.writes).onFinalize(socket.endOfOutput)
          }.parJoinUnbounded
          val echoClient =
            Stream
              .eval(client.write(Chunk.array(msg.getBytes)))
              .onFinalize(client.endOfOutput)
              .drain ++ client.reads.chunkAll.map(chunk => new String(chunk.toArray))
          echoClient.concurrently(echoServer)
        }
        .compile
        .toVector
        .map { msgs =>
          assertEquals(msgs, Vector(msg))
        }
    }

    test("read after timed out read") {
      val setup = for {
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO].connect(serverSocket.address)
      } yield (serverSocket.accept, client)
      Stream
        .resource(setup)
        .flatMap { case (server, client) =>
          val echoServer = server.flatMap(c => c.writes(c.reads).attempt)
          val msg = Chunk.array("Hello!".getBytes)
          val prg =
            client.write(msg) *>
              client.readN(msg.size) *>
              (client.readN(msg.size) *> IO.raiseError(new AssertionError("didn't timeout")))
                .timeoutTo(100.millis, IO.unit) *>
              client.write(msg) *>
              client.readN(msg.size).flatMap(c => IO(assertEquals(c, msg)))
          Stream.eval(prg).concurrently(echoServer)
        }
        .compile
        .drain
    }

    test("can shutdown a socket that's pending a read") {
      Network[IO].bind(SocketAddress.Wildcard).use { serverSocket =>
        Network[IO].connect(serverSocket.address).use { _ =>
          serverSocket.accept.head.flatMap(_.reads).compile.drain.timeout(2.seconds).recover {
            case _: TimeoutException => ()
          }
        }
      }
    }

    test("accepted socket closes timely") {
      Network[IO].bind(SocketAddress.Wildcard).use { serverSocket =>
        serverSocket.accept.foreach(_ => IO.sleep(1.second)).compile.drain.background.surround {
          Network[IO].connect(serverSocket.address).use { client =>
            client.read(1).assertEquals(None)
          }
        }
      }
    }

    test("sockets are released at the end of the resource scope") {
      val f =
        Network[IO].bind(SocketAddress.port(port"9071")).use { serverSocket =>
          serverSocket.accept.foreach(_ => IO.sleep(1.second)).compile.drain.background.surround {
            Network[IO].connect(serverSocket.address).use { client =>
              client.read(1).assertEquals(None)
            }
          }
        }
      f >> f >> f
    }

    test("endOfOutput / endOfInput ignores ENOTCONN") {
      Network[IO].bind(SocketAddress.Wildcard).use { serverSocket =>
        Network[IO]
          .connect(serverSocket.address)
          .surround(IO.sleep(100.millis))
          .background
          .surround {
            serverSocket.accept
              .take(1)
              .foreach { socket =>
                socket.write(Chunk.array("fs2.rocks".getBytes)) *>
                  IO.sleep(1.second) *>
                  socket.endOfOutput *> socket.endOfInput
              }
              .compile
              .drain
          }
      }
    }

    test("sendFile - sends data from file to socket from the offset") {
      val content = "Hello, world!"
      val offset = 7L
      val count = 5L
      val expected = "world"
      val chunkSize = 2

      val setup = for {
        tempFile <- Files[IO].tempFile
        _ <- Stream
          .emits(content.getBytes)
          .covary[IO]
          .through(Files[IO].writeAll(tempFile))
          .compile
          .drain
          .toResource
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO].connect(serverSocket.address)
      } yield (tempFile, serverSocket.accept, client)

      Stream
        .resource(setup)
        .flatMap { case (tempFile, server, client) =>
          val serverStream = server.head.flatMap { socket =>
            Stream.eval(socket.reads.through(text.utf8.decode).compile.string)
          }
          val clientStream =
            Stream.resource(Files[IO].open(tempFile, Flags.Read)).flatMap { fileHandle =>
              client.sendFile(fileHandle, offset, count, chunkSize).drain ++
                Stream.eval(client.endOfOutput)
            }
          serverStream.concurrently(clientStream)
        }
        .compile
        .lastOrError
        .map { received =>
          assertEquals(received, expected)
        }
    }
  }
}
