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

import javax.net.ssl.{SNIHostName, SSLContext}

import cats.effect.{IO, Resource}
import cats.syntax.all._

import com.comcast.ip4s._
import java.io.FileNotFoundException
import java.security.Security
import javax.net.ssl.SSLHandshakeException

import fs2.io.file.Path

class TLSSocketSuite extends TLSSuite {
  val size = 8192

  group("TLSSocket") {
    group("google") {
      def googleSetup(protocol: String) =
        for {
          tlsContext <- Resource.eval(Network[IO].tlsContext.system)
          socket <- Network[IO].connect(SocketAddress(host"google.com", port"443"))
          tlsSocket <- tlsContext
            .clientBuilder(socket)
            .withParameters(
              TLSParameters(
                serverNames = List(new SNIHostName("www.google.com")).some,
                protocols = List(protocol).some
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

      List("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").foreach { protocol =>
        val supportedByPlatform: Boolean =
          Either
            .catchNonFatal(SSLContext.getInstance(protocol))
            .isRight

        val enabled: Boolean =
          Either
            .catchNonFatal {
              val disabledAlgorithms = Security.getProperty("jdk.tls.disabledAlgorithms")
              !disabledAlgorithms.contains(protocol)
            }
            .getOrElse(false)

        if (!supportedByPlatform)
          test(s"$protocol - not supported by this platform".ignore) {}
        else if (!enabled)
          test(s"$protocol - disabled on this platform".ignore) {}
        else {
          writesBeforeReading(protocol)
          readsBeforeWriting(protocol)
        }
      }
    }

    test("echo") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- Resource.eval(testTlsContext)
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO].connect(serverSocket.address).flatMap(tlsContext.client(_))
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client =
            Stream.exec(clientSocket.write(msg)).onFinalize(clientSocket.endOfOutput) ++
              clientSocket.reads.take(msg.size.toLong)

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .assertEquals(msg)
    }

    test("error") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- Resource.eval(Network[IO].tlsContext.system)
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO].connect(serverSocket.address).flatMap(tlsContext.client(_))
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client =
            Stream.exec(clientSocket.write(msg)).onFinalize(clientSocket.endOfOutput) ++
              clientSocket.reads.take(msg.size.toLong)

          // client gets closed pipe error when server gets handshake ex
          client.mask.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .intercept[SSLHandshakeException]
    }

    test("echo insecure client with Endpoint verification") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        clientContext <- Resource.eval(TLSContext.Builder.forAsync[IO].insecure)
        tlsContext <- Resource.eval(testTlsContext)
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO]
          .connect(serverSocket.address)
          .flatMap(s =>
            clientContext
              .clientBuilder(s)
              .withParameters(
                TLSParameters.apply(endpointIdentificationAlgorithm = Some("HTTPS"))
              ) // makes test fail if using X509TrustManager, passes if using X509ExtendedTrustManager
              .build
          )
      } yield serverSocket.accept.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client =
            Stream.exec(clientSocket.write(msg)).onFinalize(clientSocket.endOfOutput) ++
              clientSocket.reads.take(msg.size.toLong)

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .assertEquals(msg)
    }

    test("endOfOutput during handshake results in termination") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      def limitWrites(raw: Socket[IO], limit: Int): Socket[IO] = new Socket[IO] {
        def endOfInput = raw.endOfInput
        def endOfOutput = raw.endOfOutput
        @deprecated("", "")
        def isOpen = raw.isOpen
        @deprecated("", "")
        def localAddress = raw.localAddress
        def peerAddress = raw.peerAddress
        def read(maxBytes: Int) = raw.read(maxBytes)
        def readN(numBytes: Int) = raw.readN(numBytes)
        def reads = raw.reads
        @deprecated("", "")
        def remoteAddress = raw.remoteAddress
        def writes = raw.writes

        def address = raw.address
        def getOption[A](key: SocketOption.Key[A]) = raw.getOption(key)
        def setOption[A](key: SocketOption.Key[A], value: A) = raw.setOption(key, value)
        def supportedOptions = raw.supportedOptions

        private var totalWritten: Long = 0
        def write(bytes: Chunk[Byte]) =
          if (totalWritten >= limit) endOfOutput
          else {
            val b = bytes.take(limit)
            raw.write(b) >> IO(totalWritten += b.size)
          }
      }

      val setup = for {
        tlsContext <- Resource.eval(testTlsContext)
        serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
        client <- Network[IO].connect(serverSocket.address).flatMap { rawClient =>
          tlsContext.clientBuilder(rawClient).withLogger(logger).build
        }
      } yield serverSocket.accept
        .flatMap(s => Stream.resource(tlsContext.server(limitWrites(s, 100)))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client =
            Stream.exec(clientSocket.write(msg)).onFinalize(clientSocket.endOfOutput) ++
              clientSocket.reads.take(msg.size.toLong)

          client.concurrently(echoServer)
        }
        .compile
        .drain
        .intercept[javax.net.ssl.SSLException]
    }
  }

  group("TLSContextBuilder") {
    test("fromKeyStoreResource - not found") {
      Network[IO].tlsContext
        .fromKeyStoreResource("does-not-exist.jks", Array.empty[Char], Array.empty[Char])
        .intercept[IOException]
    }

    test("fromKeyStoreFile - not found") {
      Network[IO].tlsContext
        .fromKeyStoreFile(Path("does-not-exist.jks"), Array.empty[Char], Array.empty[Char])
        .intercept[FileNotFoundException]
    }
  }
}
