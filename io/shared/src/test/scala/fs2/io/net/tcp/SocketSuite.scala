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
package tcp

import cats.effect.IO
import com.comcast.ip4s._

import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import fs2.io.file._

class SocketSuite extends Fs2Suite with SocketSuitePlatform {

  val timeout = 30.seconds

  val setup = for {
    serverSetup <- Network[IO].serverResource(address = Some(ip"127.0.0.1"))
    (bindAddress, server) = serverSetup
    clients = Stream
      .resource(
        Network[IO].client(bindAddress, options = setupOptionsPlatform)
      )
      .repeat
  } yield server -> clients

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
            socket.endOfOutput *> socket.localAddress.product(socket.remoteAddress)
          }

          val clientSocketAddresses =
            clients
              .take(1)
              .evalMap { socket =>
                socket.endOfOutput *> socket.localAddress.product(socket.remoteAddress)
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
        port <- Network[IO].serverResource(Some(ip"127.0.0.1")).use(s => IO.pure(s._1.port))
        _ <- Network[IO]
          .client(SocketAddress(host"localhost", port))
          .use_
          .interceptMessage[ConnectException]("Connection refused")
      } yield ()

      val addressAlreadyInUse =
        Network[IO].serverResource(Some(ip"127.0.0.1")).map(_._1).use { bindAddress =>
          Network[IO]
            .serverResource(Some(bindAddress.host), Some(bindAddress.port))
            .use_
            .interceptMessage[BindException]("Address already in use")
        }

      val unknownHost = Network[IO]
        .client(SocketAddress.fromString("not.example.com:80").get)
        .use_
        .attempt
        .map {
          case Left(ex: UnknownHostException) =>
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
        serverSetup <- Network[IO].serverResource(Some(ip"127.0.0.1"), None, opts)
        (bindAddress, server) = serverSetup
        client <- Network[IO].client(bindAddress, opts)
      } yield (server, client)

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
        serverSetup <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (bindAddress, server) = serverSetup
        client <- Network[IO].client(bindAddress)
      } yield (server, client)
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
      Network[IO].serverResource().use { case (bindAddress, clients) =>
        Network[IO].client(bindAddress).use { _ =>
          clients.head.flatMap(_.reads).compile.drain.timeout(2.seconds).recover {
            case _: TimeoutException => ()
          }
        }
      }
    }

    test("accepted socket closes timely") {
      Network[IO].serverResource().use { case (bindAddress, clients) =>
        clients.foreach(_ => IO.sleep(1.second)).compile.drain.background.surround {
          Network[IO].client(bindAddress).use { client =>
            client.read(1).assertEquals(None)
          }
        }
      }
    }

    test("sockets are released at the end of the resource scope") {
      val f =
        Network[IO].serverResource(port = Some(port"9071")).use { case (bindAddress, clients) =>
          clients.foreach(_ => IO.sleep(1.second)).compile.drain.background.surround {
            Network[IO].client(bindAddress).use { client =>
              client.read(1).assertEquals(None)
            }
          }
        }
      f >> f >> f
    }

    test("endOfOutput / endOfInput ignores ENOTCONN") {
      Network[IO].serverResource().use { case (bindAddress, clients) =>
        Network[IO].client(bindAddress).surround(IO.sleep(100.millis)).background.surround {
          clients
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
        serverSetup <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (bindAddress, server) = serverSetup
        client <- Network[IO].client(bindAddress)
      } yield (tempFile, server, client)

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
