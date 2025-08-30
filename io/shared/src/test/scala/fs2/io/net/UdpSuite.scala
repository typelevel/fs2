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
package udp

import cats.effect.IO
import cats.syntax.all._

import com.comcast.ip4s._

import scala.concurrent.duration._

class UdpSuite extends Fs2Suite {
  private def sendAndReceive(socket: DatagramSocket[IO], toSend: Datagram): IO[Datagram] =
    socket
      .write(toSend) >> socket.read.timeoutTo(1.second, IO.defer(sendAndReceive(socket, toSend)))

  private def sendAndReceiveBytes(socket: DatagramSocket[IO], bytes: Chunk[Byte]): IO[Datagram] =
    socket
      .write(bytes) >> socket.read.timeoutTo(1.second, IO.defer(sendAndReceiveBytes(socket, bytes)))

  group("udp") {
    test("echo one") {
      val msg = Chunk.array("Hello, world!".getBytes)
      Stream
        .resource(Network[IO].bindDatagramSocket(SocketAddress.Wildcard))
        .flatMap { serverSocket =>
          println("checking address 1")
          val serverAddress = SocketAddress(ip"127.0.0.1", serverSocket.address.asIpUnsafe.port)
          println("checking address 2")
          val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
          val client =
            Stream.resource(Network[IO].bindDatagramSocket(SocketAddress.Wildcard)).evalMap {
              clientSocket =>
                sendAndReceive(clientSocket, Datagram(serverAddress, msg))
            }
          client.concurrently(server)
        }
        .compile
        .lastOrError
        .map(_.bytes)
        .assertEquals(msg)
    }

    test("echo lots") {
      val msgs = (1 to 20).toVector.map(n => Chunk.array(("Hello, world! " + n).getBytes))
      val numClients = 50
      val numParallelClients = 10
      val expected = Vector
        .fill(numClients)(msgs.map(b => new String(b.toArray)))
        .flatten
        .sorted

      Stream
        .resource(Network[IO].bindDatagramSocket())
        .flatMap { serverSocket =>
          val serverAddress = SocketAddress(ip"127.0.0.1", serverSocket.address.asIpUnsafe.port)
          val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
          val client = Stream.resource(Network[IO].bindDatagramSocket()).flatMap { clientSocket =>
            Stream
              .emits(msgs.map(msg => Datagram(serverAddress, msg)))
              .evalMap(msg => sendAndReceive(clientSocket, msg))
          }
          val clients = Stream
            .constant(client)
            .take(numClients.toLong)
            .parJoin(numParallelClients)
          clients.concurrently(server)
        }
        .compile
        .toVector
        .map(_.map(p => new String(p.bytes.toArray)).sorted)
        .assertEquals(expected)
    }

    test("echo connected") {
      val msg = Chunk.array("Hello, world!".getBytes)
      Stream
        .resource(Network[IO].bindDatagramSocket())
        .flatMap { serverSocket =>
          val serverAddress = serverSocket.address.asIpUnsafe
          val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
          val client = Stream.resource(Network[IO].bindDatagramSocket()).evalMap { clientSocket =>
            clientSocket.connect(serverAddress) >> sendAndReceiveBytes(clientSocket, msg)
          }
          client.concurrently(server)
        }
        .compile
        .lastOrError
        .map(_.bytes)
        .assertEquals(msg)
    }

    test("multicast") {
      val group = mip"239.10.10.10"
      val groupJoin = MulticastJoin.asm(group)
      val msg = Chunk.array("Hello, world!".getBytes)
      val outgoingInterface =
        // Get first non-loopback interface with an IPv4 address
        Network[IO].interfaces.getAll.map { interfaces =>
          interfaces.values
            .filterNot(_.isLoopback)
            .flatMap(iface =>
              iface.addresses.filter(_.address.fold(_ => true, _ => false)).as(iface)
            )
            .head
        }
      Stream
        .eval(outgoingInterface)
        .flatMap { out =>
          Stream
            .resource(
              Network[IO]
                .bindDatagramSocket(
                  options = List(SocketOption.multicastTtl(1), SocketOption.multicastInterface(out))
                )
                .evalMap { serverSocket =>
                  Network[IO].interfaces.getAll.flatMap { interfaces =>
                    interfaces.values.toList
                      .filter(iface =>
                        iface.addresses.exists(_.address.fold(_ => true, _ => false))
                      )
                      .traverse_(iface => serverSocket.join(groupJoin, iface))
                      .as(serverSocket)
                  }
                }
            )
            .flatMap { serverSocket =>
              val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
              val client =
                Stream.resource(Network[IO].bindDatagramSocket()).flatMap { clientSocket =>
                  val to = SocketAddress(group.address, serverSocket.address.asIpUnsafe.port)
                  Stream.eval(clientSocket.write(msg, to) >> clientSocket.read)
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
}
