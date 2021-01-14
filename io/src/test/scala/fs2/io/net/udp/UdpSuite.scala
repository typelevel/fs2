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

import java.net.{Inet4Address, NetworkInterface, StandardProtocolFamily}

import cats.effect.IO
import cats.syntax.all._

import com.comcast.ip4s._

import CollectionCompat._

class UdpSuite extends Fs2Suite {
  group("udp") {
    test("echo one") {
      val msg = Chunk.array("Hello, world!".getBytes)
      Stream
        .resource(Network[IO].openDatagramSocket())
        .flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map(_.port).flatMap { serverPort =>
            val serverAddress = SocketAddress(ip"127.0.0.1", serverPort)
            val server = serverSocket.reads
              .evalMap(packet => serverSocket.write(packet))
              .drain
            val client = Stream.resource(Network[IO].openDatagramSocket()).flatMap { clientSocket =>
              Stream(Datagram(serverAddress, msg))
                .through(clientSocket.writes)
                .drain ++ Stream.eval(clientSocket.read)
            }
            server.mergeHaltBoth(client)
          }
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
        .resource(Network[IO].openDatagramSocket())
        .flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map(_.port).flatMap { serverPort =>
            val serverAddress = SocketAddress(ip"127.0.0.1", serverPort)
            val server = serverSocket.reads
              .evalMap(packet => serverSocket.write(packet))
              .drain
            val client = Stream.resource(Network[IO].openDatagramSocket()).flatMap { clientSocket =>
              Stream
                .emits(msgs.map(msg => Datagram(serverAddress, msg)))
                .flatMap { msg =>
                  Stream.exec(clientSocket.write(msg)) ++ Stream.eval(clientSocket.read)
                }
            }
            val clients = Stream
              .constant(client)
              .take(numClients.toLong)
              .parJoin(numParallelClients)
            server.mergeHaltBoth(clients)
          }
        }
        .compile
        .toVector
        .map(_.map(p => new String(p.bytes.toArray)).sorted)
        .assertEquals(expected)
    }

    test("multicast".ignore) {
      // Fails often based on routing table of host machine
      val group = mip"232.10.10.10"
      val groupJoin = MulticastJoin.asm(group)
      val msg = Chunk.array("Hello, world!".getBytes)
      Stream
        .resource(
          Network[IO].openDatagramSocket(
            options = List(SocketOption.multicastTtl(1)),
            protocolFamily = Some(StandardProtocolFamily.INET)
          )
        )
        .flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map(_.port).flatMap { serverPort =>
            val v4Interfaces =
              NetworkInterface.getNetworkInterfaces.asScala.toList.filter { interface =>
                interface.getInetAddresses.asScala.exists(_.isInstanceOf[Inet4Address])
              }
            val server = Stream
              .exec(
                v4Interfaces.traverse_(interface => serverSocket.join(groupJoin, interface))
              ) ++
              serverSocket.reads
                .evalMap(packet => serverSocket.write(packet))
                .drain
            val client = Stream.resource(Network[IO].openDatagramSocket()).flatMap { clientSocket =>
              Stream(Datagram(SocketAddress(group.address, serverPort), msg))
                .through(clientSocket.writes)
                .drain ++ Stream.eval(clientSocket.read)
            }
            server.mergeHaltBoth(client)
          }
        }
        .compile
        .lastOrError
        .map(_.bytes)
        .assertEquals(msg)
    }
  }
}
