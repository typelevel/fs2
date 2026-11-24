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

import java.io.IOException

class TLSSocketSuite extends TLSSuite {
  override def munitIOTimeout = 1.minute

  group("TLSSocket") {
    group("google") {
      def googleSetup(version: String) =
        for {
          tlsContext <- Network[IO].tlsContext.systemResource
          socket <- Network[IO].connect(SocketAddress(host"google.com", port"443"))
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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO]
          .connect(serverSocket.address)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO]
          .connect(serverSocket.address)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO]
          .connect(serverSocket.address)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO]
          .connect(serverSocket.address)
          .flatMap(
            clientContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield serverSocket.accept.flatMap(s =>
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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO]
          .connect(serverSocket.address)
          .flatMap(
            tlsContext
              .clientBuilder(_)
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          )
      } yield serverSocket.accept.flatMap(s =>
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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO].connect(serverSocket.address).flatMap(clientContext.client(_))
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

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
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client = Network[IO].connect(serverSocket.address).flatMap(tlsContext.client(_))
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

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

  test("present application protocol") {
    val setup = for {
      tlsContext <- testTlsContext
      serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
      client = Network[IO]
        .connect(serverSocket.address)
        .flatMap(
          tlsContext
            .clientBuilder(_)
            .withParameters(
              TLSParameters(serverName = Some("Unknown"), protocolPreferences = Some(List("h2")))
            )
            .build
        )
    } yield serverSocket.accept.flatMap(s =>
      Stream.resource(
        tlsContext
          .serverBuilder(s)
          .withParameters(TLSParameters(protocolPreferences = Some(List("h2"))))
          .build
      )
    ) -> client

    Stream
      .resource(setup)
      .flatMap { case (server, clientSocket) =>
        val echoServer = server.map { socket =>
          socket.reads.chunks.foreach(socket.write(_))
        }.parJoinUnbounded

        val client = Stream.resource(clientSocket).evalMap { clientSocket =>
          clientSocket.applicationProtocol
        }

        client.concurrently(echoServer)
      }
      .compile
      .lastOrError
      .map(assertEquals(_, "h2"))
  }

  test("missing application protocol") {
    val setup = for {
      tlsContext <- testTlsContext
      serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
      client = Network[IO]
        .connect(serverSocket.address)
        .flatMap(
          tlsContext
            .clientBuilder(_)
            .withParameters(TLSParameters(serverName = Some("Unknown")))
            .build
        )
    } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

    Stream
      .resource(setup)
      .flatMap { case (server, clientSocket) =>
        val echoServer = server.map { socket =>
          socket.reads.chunks.foreach(socket.write(_))
        }.parJoinUnbounded

        val client = Stream.resource(clientSocket).evalMap { clientSocket =>
          clientSocket.applicationProtocol
        }

        client.concurrently(echoServer)
      }
      .compile
      .lastOrError
      .map(assertEquals(_, ""))
  }

  group("releasing the socket finishes") {
    // Releasing a TLS socket is uncancelable, so a hang is detected without waiting on it
    def finishes(use: IO[Unit]): IO[Unit] =
      use.start.flatMap(
        _.joinWithNever.timeoutTo(10.seconds, IO(fail("releasing the TLS socket never finished")))
      )

    type Write = (Socket[IO], Chunk[Byte]) => Stream[IO, Int]

    // Forwards to `socket`, but writes call `onWrite`
    def interceptWrites(socket: Socket[IO], onWrite: Write): Socket[IO] =
      new Socket[IO] {
        override def writeIncremental(bytes: Chunk[Byte]): Stream[IO, Int] =
          onWrite(socket, bytes)
        def write(bytes: Chunk[Byte]): IO[Unit] = writeIncremental(bytes).compile.drain
        def writes: Pipe[IO, Byte, Nothing] = _.chunks.foreach(write)
        def read(maxBytes: Int): IO[Option[Chunk[Byte]]] = socket.read(maxBytes)
        def readN(numBytes: Int): IO[Chunk[Byte]] = socket.readN(numBytes)
        def reads: Stream[IO, Byte] = socket.reads
        def endOfInput: IO[Unit] = socket.endOfInput
        def endOfOutput: IO[Unit] = socket.endOfOutput
        def address: GenSocketAddress = socket.address
        def peerAddress: GenSocketAddress = socket.peerAddress
        def supportedOptions: IO[Set[SocketOption.Key[?]]] = socket.supportedOptions
        def getOption[A](key: SocketOption.Key[A]): IO[Option[A]] = socket.getOption(key)
        def setOption[A](key: SocketOption.Key[A], value: A): IO[Unit] =
          socket.setOption(key, value)
        @deprecated("No replacement; sockets are open until they are finalized", "3.13.0")
        def isOpen: IO[Boolean] = socket.isOpen
        @deprecated("Use address instead", "3.13.0")
        def localAddress: IO[SocketAddress[IpAddress]] = socket.localAddress
        @deprecated("Use peerAddress instead", "3.13.0")
        def remoteAddress: IO[SocketAddress[IpAddress]] = socket.remoteAddress
      }

    val msg = Chunk.array("Hello, world!".getBytes)

    // Connects a client whose underlying socket writes with `write`, and returns what `use`
    // returns and what the server read
    def connect[A](write: Write)(
        use: TLSSocket[IO] => IO[A]
    ): IO[(A, Either[Throwable, Chunk[Byte]])] = {
      val setup = for {
        tlsContext <- testTlsContext
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
      } yield (tlsContext, serverSocket)

      setup.use { case (tlsContext, serverSocket) =>
        val server = serverSocket.accept.head
          .flatMap(s => Stream.resource(tlsContext.server(s)).flatMap(_.reads))
          .compile
          .to(Chunk)
          .attempt

        val client = Network[IO]
          .connect(serverSocket.address)
          .flatMap { socket =>
            tlsContext
              .clientBuilder(interceptWrites(socket, write))
              .withParameters(TLSParameters(serverName = Some("Unknown")))
              .build
          }
          .use(use)

        server.start.flatMap { serverFiber =>
          IO.ref(Option.empty[A]).flatMap { result =>
            finishes(client.flatMap(a => result.set(Some(a)))) >>
              (result.get.map(_.get), serverFiber.joinWithNever).tupled
          }
        }
      }
    }

    test("after the socket fails a write") {
      IO.ref(false).flatMap { failWrites =>
        val write: Write = (socket, bytes) =>
          Stream.eval(failWrites.get).flatMap { fail =>
            if (fail) Stream.raiseError[IO](new IOException("write failed"))
            else socket.writeIncremental(bytes)
          }
        connect(write) { tlsSocket =>
          failWrites.set(true) >> tlsSocket.write(msg).attempt
        }.map { case (result, _) => assert(result.isLeft, "the write succeeded") }
      }
    }

    // Cancels a write of `msg` once the socket has written `written(record)` of the record s2n
    // queued and is blocked on the rest, then checks that the server still gets all of `msg`
    def cancelWrite(written: Chunk[Byte] => Chunk[Byte]): IO[Unit] =
      (IO.ref(false), IO.deferred[Unit]).flatMapN { (blockNextWrite, writeBlocked) =>
        val write: Write = (socket, bytes) =>
          Stream.eval(blockNextWrite.getAndSet(false)).flatMap { block =>
            if (block)
              socket.writeIncremental(written(bytes)) ++
                Stream.exec(writeBlocked.complete(()) >> IO.never[Unit])
            else socket.writeIncremental(bytes)
          }
        connect(write) { tlsSocket =>
          blockNextWrite.set(true) >>
            tlsSocket.write(msg).start.flatMap(writer => writeBlocked.get >> writer.cancel)
        }.map { case (_, received) => assertEquals(received, Right(msg)) }
      }

    test("after a write is canceled before reaching the socket, which still sends it") {
      cancelWrite(_ => Chunk.empty)
    }

    test("after a write is canceled partway, which still sends the rest") {
      cancelWrite(record => record.take(record.size / 2))
    }
  }
}
