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
package tls

import scala.concurrent.duration._

import cats.effect.IO
import cats.syntax.all._
import com.comcast.ip4s._

class TLSSocketSuite extends TLSSuite {
  override def munitIOTimeout = 1.minute

  group("TLSSocket") {
    group("google") {
      def googleSetup(version: String) =
        for {
          tlsContext <- Network[IO].tlsContext.systemResource
          socket <- Network[IO].client(SocketAddress(host"google.com", port"443"))
          tlsSocket <- tlsContext
            .clientBuilder(socket)
            .withParameters(
              TLSParameters(
                serverName = "www.google.com".some,
                cipherPreferences = version.some
              )
            )
            .build
        } yield tlsSocket

      val googleDotCom = "GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n"
      val httpOk = "HTTP/1.1"

      def writesBeforeReading(protocol: String) =
        test(s"$protocol - client writes before reading") {
          Stream
            .resource(googleSetup(protocol))
            .flatMap { tlsSocket =>
              Stream(googleDotCom)
                .covary[IO]
                .through(text.utf8.encode)
                .through(tlsSocket.writes)
                .onFinalize(tlsSocket.endOfOutput) ++
                tlsSocket.reads
                  .through(text.utf8.decode)
                  .through(text.lines)
            }
            .head
            .compile
            .string
            .map(_.take(httpOk.length))
            .assertEquals(httpOk)
        }

      def readsBeforeWriting(protocol: String) =
        test(s"$protocol - client reads before writing") {
          Stream
            .resource(googleSetup(protocol))
            .flatMap { socket =>
              val send = Stream(googleDotCom)
                .through(text.utf8.encode)
                .through(socket.writes)
              val receive = socket.reads
                .through(text.utf8.decode)
                .through(text.lines)

              receive.concurrently(send.delayBy(100.millis))
            }
            .head
            .compile
            .string
            .map(_.take(httpOk.length))
            .assertEquals(httpOk)
        }

      // https://github.com/aws/s2n-tls/blob/8556f56bc6386dc8bfd8cceb6a38b97215a6f32e/docs/USAGE-GUIDE.md#security-policies
      List("default", "default_tls13" /*, "rfc9151"*/ ).foreach { protocol =>
        writesBeforeReading(protocol)
        readsBeforeWriting(protocol)
      }
    }

    test("echo") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- testTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO]
          .client(serverAddress)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .assertEquals(msg)
    }

    test("empty write") {
      val setup = for {
        tlsContext <- testTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO]
          .client(serverAddress)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(Chunk.empty))
          }

          client.concurrently(echoServer)
        }
        .compile
        .drain
    }

    test("error") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- Network[IO].tlsContext.systemResource
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO]
          .client(serverAddress)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }
          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .intercept[SSLException]
    }

    test("mTLS client verification fails if client cannot authenticate") {
      val msg = Chunk.array(("Hello, world! " * 100).getBytes)

      val setup = for {
        serverContext <- testTlsContext
        clientContext <- testClientTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO]
          .client(serverAddress)
          .flatMap(
            clientContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield server.flatMap(s =>
        Stream.resource(
          serverContext
            .serverBuilder(s)
            .withParameters(TLSParameters(clientAuthType = CertAuthType.Required.some)) // mTLS
            .build
        )
      ) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }

          client.mask //
            .concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .intercept[SSLException]
    }

    test("mTLS client verification happy-path") {
      val msg = Chunk.array(("Hello, world! " * 100).getBytes)

      val setup = for {
        tlsContext <- testTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO]
          .client(serverAddress)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield server.flatMap(s =>
        Stream.resource(
          tlsContext
            .serverBuilder(s)
            .withParameters(TLSParameters(clientAuthType = CertAuthType.Required.some)) // mTLS
            .build
        )
      ) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
    }

    test("echo insecure client") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        clientContext <- Network[IO].tlsContext.insecureResource
        tlsContext <- testTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO].client(serverAddress).flatMap(clientContext.client(_))
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .assertEquals(msg)
    }

    test("blinding") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- testTlsContext
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client = Network[IO].client(serverAddress).flatMap(tlsContext.client(_))
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      val echo = Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client = Stream.resource(clientSocket).flatMap { clientSocket =>
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)
          }

          client.concurrently(echoServer)
        }
        .compile
        .drain

      val checkStarvation = IO.sleep(3.seconds).timed.flatMap { case (duration, _) =>
        IO(assert(clue(duration) < 4.seconds, "starved during blinding"))
      }

      val checkBlinding =
        echo.attempt.flatMap(result => IO(assert(clue(result).isLeft))).timed.flatMap {
          case (duration, _) => IO(assert(clue(duration) > 9.seconds, "didn't blind on error"))
        }

      checkStarvation.both(checkBlinding).void
    }
  }
}
