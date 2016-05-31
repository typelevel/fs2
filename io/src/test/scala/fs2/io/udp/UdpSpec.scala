package fs2
package io
package udp

import java.net.InetSocketAddress

import fs2.util.Task

import org.scalatest.BeforeAndAfterAll

class UdpSpec extends Fs2Spec with BeforeAndAfterAll {

  implicit val AG = AsynchronousSocketGroup()

  override def afterAll() = {
    AG.close
  }

  "udp" - {
    "echo" in {
      val msg = Chunk.bytes("Hello, world!".getBytes)
      runLog {
        open[Task]().flatMap { serverSocket =>
          Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
            val serverAddress = new InetSocketAddress("localhost", serverPort)
            val server = serverSocket.reads.evalMap { packet => serverSocket.write(packet) }.drain
            val client = open[Task]().flatMap { clientSocket =>
              Stream(Packet(serverAddress, msg)).to(clientSocket.writes).drain ++ Stream.eval(clientSocket.read)
            }
            server mergeHaltBoth client
          }
        }
      }.map(_.bytes) shouldBe Vector(msg)
    }
  }
}
