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

import cats.effect.{Blocker, IO}
import cats.implicits._

import CollectionCompat._

class UdpSpec extends Fs2Spec {
  def mkSocketGroup: Stream[IO, SocketGroup] =
    Stream.resource(Blocker[IO].flatMap(blocker => SocketGroup[IO](blocker)))

  "udp" - {
    "echo one" in {
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
        .asserting(it => assert(it.map(_.bytes) == Vector(msg)))
    }

    "echo lots" in {
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
                      Stream.eval_(clientSocket.write(msg)) ++ Stream.eval(clientSocket.read())
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
        .asserting(res =>
          assert(
            res.map(p => new String(p.bytes.toArray)).sorted == Vector
              .fill(numClients)(msgs.map(b => new String(b.toArray)))
              .flatten
              .sorted
          )
        )
    }

    "multicast" in {
      pending // Fails often based on routing table of host machine
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
                  .eval_(v4Interfaces.traverse(interface => serverSocket.join(group, interface))) ++
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
        .asserting(it => assert(it.map(_.bytes) == Vector(msg)))
    }

    "timeouts supported" in {
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
