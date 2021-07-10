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

import cats.effect.IO
import cats.effect.Resource
import com.comcast.ip4s._

import scala.concurrent.duration._

class TLSSocketSuite extends TLSSuite {
  val size = 8192

  group("TLSSocket") {
    group("google") {
      def googleSetup =
        for {
          tlsContext <- Resource.eval(Network[IO].tlsContext.system)
          socket <- Network[IO].client(SocketAddress(host"google.com", port"443"))
          tlsSocket <- tlsContext.client(socket)
        } yield tlsSocket

      val googleDotCom = "GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n"
      val httpOk = "HTTP/1.1 200 OK"

      def writesBeforeReading() =
        test(s"client writes before reading") {
          Stream
            .resource(googleSetup)
            .flatMap { tlsSocket =>
              Stream(googleDotCom)
                .covary[IO]
                .through(text.utf8Encode)
                .through(tlsSocket.writes) ++
                Stream.exec(tlsSocket.endOfOutput) ++
                tlsSocket.reads
                  .through(text.utf8Decode)
                  .through(text.lines)
            }
            .head
            .compile
            .string
            .assertEquals(httpOk)
        }

      def readsBeforeWriting() =
        test(s"client reads before writing") {
          Stream
            .resource(googleSetup)
            .flatMap { socket =>
              val send = Stream(googleDotCom)
                .through(text.utf8Encode)
                .through(socket.writes)
              val receive = socket.reads
                .through(text.utf8Decode)
                .through(text.lines)

              receive.concurrently(send.delayBy(100.millis))
            }
            .head
            .compile
            .string
            .assertEquals(httpOk)
        }

      writesBeforeReading()
      readsBeforeWriting()
    }

    test("echo") {
      val msg = Chunk.array(("Hello, world! " * 20000).getBytes)

      val setup = for {
        tlsContext <- Resource.eval(testTlsContext)
        addressAndConnections <- Network[IO].serverResource(Some(ip"127.0.0.1"))
        (serverAddress, server) = addressAndConnections
        client <- Network[IO].client(serverAddress).flatMap(tlsContext.client(_))
      } yield server.flatMap(s => Stream.resource(tlsContext.server(s))) -> client

      Stream
        .resource(setup)
        .flatMap { case (server, clientSocket) =>
          val echoServer = server.map { socket =>
            socket.reads.chunks.foreach(socket.write(_))
          }.parJoinUnbounded

          val client =
            Stream.exec(clientSocket.write(msg)) ++
              clientSocket.reads.take(msg.size.toLong)

          client.concurrently(echoServer)
        }
        .compile
        .to(Chunk)
        .assertEquals(msg)
    }
  }
}
