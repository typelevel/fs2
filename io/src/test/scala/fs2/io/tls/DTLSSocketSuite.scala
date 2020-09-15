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
package tls

import scala.concurrent.duration._

import java.net.InetSocketAddress

import cats.effect.IO
import cats.syntax.all._

import fs2.io.udp.{Packet, SocketGroup}

class DTLSSocketSuite extends TLSSuite {
  group("DTLSSocket") {
    test("echo") {
      SocketGroup[IO].use { socketGroup =>
        testTlsContext.flatMap { tlsContext =>
          socketGroup.open[IO]().use { serverSocket =>
            serverSocket.localAddress.map(_.getPort).flatMap { serverPort =>
              val serverAddress = new InetSocketAddress("localhost", serverPort)
              socketGroup.open[IO]().use { clientSocket =>
                clientSocket.localAddress.map(_.getPort).flatMap { clientPort =>
                  val clientAddress = new InetSocketAddress("localhost", clientPort)
                  val serverLogger =
                    None // Some((msg: String) => IO(println(s"\u001b[33m${msg}\u001b[0m")))
                  val clientLogger =
                    None // Some((msg: String) => IO(println(s"\u001b[32m${msg}\u001b[0m")))
                  (
                    tlsContext.dtlsServer(serverSocket, clientAddress, logger = serverLogger),
                    tlsContext.dtlsClient(clientSocket, serverAddress, logger = clientLogger)
                  ).tupled.use {
                    case (dtlsServerSocket, dtlsClientSocket) =>
                      val echoServer =
                        dtlsServerSocket
                          .reads(None)
                          .foreach(p => dtlsServerSocket.write(p, None))
                      val msg = Chunk.bytes("Hello, world!".getBytes)
                      val echoClient = Stream.sleep_[IO](500.milliseconds) ++ Stream.exec(
                        dtlsClientSocket.write(Packet(serverAddress, msg))
                      ) ++ Stream.eval(dtlsClientSocket.read())
                      echoClient
                        .concurrently(echoServer)
                        .compile
                        .toList
                        .map(it => assert(it.map(_.bytes) == List(msg)))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
