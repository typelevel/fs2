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
import cats.implicits._

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
                  .take(numClients)
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
