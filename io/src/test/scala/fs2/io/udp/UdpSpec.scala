package fs2
package io
package udp

import java.net.{InetAddress,Inet4Address,InetSocketAddress,NetworkInterface,StandardProtocolFamily}
import java.nio.channels.InterruptedByTimeoutException
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._
import org.scalatest.BeforeAndAfterAll

class UdpSpec extends Fs2Spec with BeforeAndAfterAll {

  implicit val AG = AsynchronousSocketGroup()

  override def afterAll() = {
    AG.close
  }

  "udp" - {
    "echo one" in {
      val msg = Chunk.bytes("Hello, world!".getBytes)
      runLog {
        open[IO]().flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
            val serverAddress = new InetSocketAddress("localhost", serverPort)
            val server = serverSocket.reads().evalMap { packet => serverSocket.write(packet) }.drain
            val client = open[IO]().flatMap { clientSocket =>
              Stream(Packet(serverAddress, msg)).covary[IO].to(clientSocket.writes()).drain ++ Stream.eval(clientSocket.read())
            }
            server mergeHaltBoth client
          }
        }
      }.map(_.bytes) shouldBe Vector(msg)
    }

    "echo lots" in {
      val msgs = (1 to 20).toVector.map { n => Chunk.bytes(("Hello, world! " + n).getBytes) }
      val numClients = 50
      val numParallelClients = 10
      runLog {
        open[IO]().flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
            val serverAddress = new InetSocketAddress("localhost", serverPort)
            val server = serverSocket.reads().evalMap { packet => serverSocket.write(packet) }.drain
            val client = open[IO]().flatMap { clientSocket =>
              Stream.emits(msgs.map { msg => Packet(serverAddress, msg) }).covary[IO].flatMap { msg =>
                Stream.eval_(clientSocket.write(msg)) ++ Stream.eval(clientSocket.read())
              }
            }
            val clients = Stream.constant(client).take(numClients).join(numParallelClients)
            server mergeHaltBoth clients
          }
        }
      }.map(p => new String(p.bytes.toArray)).sorted shouldBe Vector.fill(numClients)(msgs.map(b => new String(b.toArray))).flatten.sorted
    }

    "multicast" in {
      pending // Fails often based on routing table of host machine
      val group = InetAddress.getByName("232.10.10.10")
      val msg = Chunk.bytes("Hello, world!".getBytes)
      runLog {
        open[IO](
          protocolFamily = Some(StandardProtocolFamily.INET),
          multicastTTL = Some(1)
        ).flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
            val v4Interfaces = NetworkInterface.getNetworkInterfaces.asScala.toList.filter { interface =>
              interface.getInetAddresses.asScala.exists(_.isInstanceOf[Inet4Address])
            }
            val server = Stream.eval_(v4Interfaces.traverse(interface => serverSocket.join(group, interface))) ++
              serverSocket.reads().evalMap { packet => serverSocket.write(packet) }.drain
            val client = open[IO]().flatMap { clientSocket =>
              Stream(Packet(new InetSocketAddress(group, serverPort), msg)).covary[IO].to(clientSocket.writes()).drain ++ Stream.eval(clientSocket.read())
            }
            server mergeHaltBoth client
          }
        }
      }.map(_.bytes) shouldBe Vector(msg)
    }

    "timeouts supported" in {
      an[InterruptedByTimeoutException] should be thrownBy {
        runLog {
          open[IO]().flatMap { socket =>
            socket.reads(timeout = Some(50.millis))
          }
        }
      }
    }
  }
}
