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

import cats.effect.{IO, Resource}
import com.comcast.ip4s._

class DTLSSocketSuite extends TLSSuite {
  group("DTLSSocket") {
    test("echo") {
      val msg = Chunk.array("Hello, world!".getBytes)

      def address(s: DatagramSocket[IO]) =
        Resource
          .eval(s.localAddress)
          .map(a => SocketAddress(ip"127.0.0.1", a.port))

      val setup = for {
        tlsContext <- Resource.eval(testTlsContext)
        serverSocket <- Network[IO].openDatagramSocket()
        serverAddress <- address(serverSocket)
        clientSocket <- Network[IO].openDatagramSocket()
        clientAddress <- address(clientSocket)
        tlsServerSocket <- tlsContext.dtlsServerBuilder(serverSocket, clientAddress).withLogger(logger).build
        tlsClientSocket <- tlsContext.dtlsClientBuilder(clientSocket, serverAddress).withLogger(logger).build
      } yield (tlsServerSocket, tlsClientSocket, serverAddress)

      Stream
        .resource(setup)
        .flatMap { case (serverSocket, clientSocket, serverAddress) =>
          val echoServer =
            serverSocket.reads
              .foreach(serverSocket.write(_))
          val echoClient = Stream.eval {
            IO.sleep(500.millis) >>
              clientSocket.write(Datagram(serverAddress, msg)) >>
              clientSocket.read
          }

          echoClient.concurrently(echoServer)
        }
        .compile
        .lastOrError
        .map(_.bytes)
        .assertEquals(msg)
    }
  }
}
