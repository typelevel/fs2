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
package udp

import java.net.{
  Inet4Address,
  InetAddress,
  InetSocketAddress,
  NetworkInterface,
  StandardProtocolFamily
}
import java.nio.channels.InterruptedByTimeoutException
import scala.concurrent.duration._

import cats.effect.IO
import cats.syntax.all._

import CollectionCompat._

class UdpSuite extends Fs2Suite {
  def mkSocketGroup: Stream[IO, SocketGroup] =
    Stream.resource(SocketGroup[IO])

  group("udp") {
    test("echo one") {
      val msg = Chunk.bytes("Hello, world!".getBytes)
      mkSocketGroup
        .flatMap { socketGroup =>
          Stream
            .resource(socketGroup.open[IO]())
            .flatMap { serverSocket =>
              Stream.eval(serverSocket.localAddress).map(_.getPort).flatMap { serverPort =>
                val serverAddress = new InetSocketAddress("localhost", serverPort)
                val server = serverSocket
                  .reads()
                  .evalMap(packet => serverSocket.write(packet))
                  .drain
                val client = Stream.resource(socketGroup.open[IO]()).flatMap { clientSocket =>
                  Stream(Packet(serverAddress, msg))
                    .through(clientSocket.writes())
                    .drain ++ Stream.eval(clientSocket.read())
                }
                server.mergeHaltBoth(client)
              }
            }
        }
        .compile
        .toVector
        .map(it => assert(it.map(_.bytes) == Vector(msg)))
    }

    test("echo lots") {
      val msgs = (1 to 20).toVector.map(n => Chunk.bytes(("Hello, world! " + n).getBytes))
      val numClients = 50
      val numParallelClients = 10
      mkSocketGroup
        .flatMap { socketGroup =>
          Stream
            .resource(socketGroup.open[IO]())
            .flatMap { serverSocket =>
              Stream.eval(serverSocket.localAddress).map(_.getPort).flatMap { serverPort =>
                val serverAddress = new InetSocketAddress("localhost", serverPort)
                val server = serverSocket
                  .reads()
                  .evalMap(packet => serverSocket.write(packet))
                  .drain
                val client = Stream.resource(socketGroup.open[IO]()).flatMap { clientSocket =>
                  Stream
                    .emits(msgs.map(msg => Packet(serverAddress, msg)))
                    .flatMap { msg =>
                      Stream.exec(clientSocket.write(msg)) ++ Stream.eval(clientSocket.read())
                    }
                }
                val clients = Stream
                  .constant(client)
                  .take(numClients.toLong)
                  .parJoin(numParallelClients)
                server.mergeHaltBoth(clients)
              }
            }
        }
        .compile
        .toVector
        .map(res =>
          assert(
            res.map(p => new String(p.bytes.toArray)).sorted == Vector
              .fill(numClients)(msgs.map(b => new String(b.toArray)))
              .flatten
              .sorted
          )
        )
    }

    test("multicast".ignore) {
      // Fails often based on routing table of host machine
      val group = InetAddress.getByName("232.10.10.10")
      val msg = Chunk.bytes("Hello, world!".getBytes)
      mkSocketGroup
        .flatMap { socketGroup =>
          Stream
            .resource(
              socketGroup.open[IO](
                protocolFamily = Some(StandardProtocolFamily.INET),
                multicastTTL = Some(1)
              )
            )
            .flatMap { serverSocket =>
              Stream.eval(serverSocket.localAddress).map(_.getPort).flatMap { serverPort =>
                val v4Interfaces =
                  NetworkInterface.getNetworkInterfaces.asScala.toList.filter { interface =>
                    interface.getInetAddresses.asScala.exists(_.isInstanceOf[Inet4Address])
                  }
                val server = Stream
                  .exec(
                    v4Interfaces.traverse_(interface => serverSocket.join(group, interface))
                  ) ++
                  serverSocket
                    .reads()
                    .evalMap(packet => serverSocket.write(packet))
                    .drain
                val client = Stream.resource(socketGroup.open[IO]()).flatMap { clientSocket =>
                  Stream(Packet(new InetSocketAddress(group, serverPort), msg))
                    .through(clientSocket.writes())
                    .drain ++ Stream.eval(clientSocket.read())
                }
                server.mergeHaltBoth(client)
              }
            }
        }
        .compile
        .toVector
        .map(it => assert(it.map(_.bytes) == Vector(msg)))
    }

    test("timeouts supported") {
      mkSocketGroup
        .flatMap { socketGroup =>
          Stream
            .resource(socketGroup.open[IO]())
            .flatMap(socket => socket.reads(timeout = Some(50.millis)))
        }
        .compile
        .drain
        .assertThrows[InterruptedByTimeoutException]
    }
  }
}
