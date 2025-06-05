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
import cats.syntax.all._

import com.comcast.ip4s._

import scala.concurrent.duration._

import fs2.io.file.Files

class UnixDatagramSuite extends Fs2Suite {

  private def sendAndReceive(
      socket: DatagramSocket[IO],
      bytes: Chunk[Byte],
      address: UnixSocketAddress
  ): IO[Datagram] =
    socket
      .write(bytes, address) >> socket.read.timeoutTo(
      1.second,
      IO.defer(sendAndReceive(socket, bytes, address))
    )

  private def sendAndReceiveBytes(socket: DatagramSocket[IO], bytes: Chunk[Byte]): IO[Datagram] =
    socket
      .write(bytes) >> socket.read.timeoutTo(1.second, IO.defer(sendAndReceiveBytes(socket, bytes)))

  val opts = List(SocketOption.unixSocketDeleteIfExists(true))
  val tempUnixSocketAddress = Files[IO].tempFile.map(f => UnixSocketAddress(f.toString))

  test("echo one") {
    val msg = Chunk.array("Hello, world!".getBytes)
    Stream
      .resource((tempUnixSocketAddress, tempUnixSocketAddress).tupled)
      .flatMap { case (serverAddress, clientAddress) =>

        Stream
          .resource(Network[IO].bindDatagramSocket(serverAddress, opts))
          .flatMap { serverSocket =>
            println(s"Bound server socket: " + serverSocket)
            // val server = Stream.repeatEval(serverSocket.readGen).foreach(serverSocket.write)
            val server = Stream.repeatEval(serverSocket.readGen).foreach { p =>
              println(s"Server got: $p"); serverSocket.write(p)
            }
            val client =
              Stream.resource(Network[IO].bindDatagramSocket(clientAddress, opts)).evalMap {
                clientSocket =>
                  println(s"Bound client socket: " + clientSocket)
                  sendAndReceive(clientSocket, msg, serverAddress)
              }
            client.concurrently(server)
          }
      }
      .compile
      .lastOrError
      .map(_.bytes)
      .assertEquals(msg)
  }

  test("echo connected") {
    val msg = Chunk.array("Hello, world!".getBytes)
    Stream
      .resource((tempUnixSocketAddress, tempUnixSocketAddress).tupled)
      .flatMap { case (serverAddress, clientAddress) =>
        Stream
          .resource(Network[IO].bindDatagramSocket(serverAddress, opts))
          .flatMap { serverSocket =>
            val server = Stream.repeatEval(serverSocket.readGen).foreach(serverSocket.write)
            val client =
              Stream.resource(Network[IO].bindDatagramSocket(clientAddress, opts)).evalMap {
                clientSocket =>
                  clientSocket.connect(serverAddress) >> sendAndReceiveBytes(clientSocket, msg)
              }
            client.concurrently(server)
          }
      }
      .compile
      .lastOrError
      .map(_.bytes)
      .assertEquals(msg)
  }
}
