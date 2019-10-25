/*
rule = v2
 */
package fix

import java.net.InetSocketAddress

import cats.effect.{ContextShift, IO}
import fs2.{Chunk, Stream}
import fs2.io.udp.{AsynchronousSocketGroup, Packet, open}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import cats.implicits._

import scala.concurrent.ExecutionContext

object UdpSocketGroup {

  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global

  implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(executionContext)

  implicit val AG = AsynchronousSocketGroup()

  val msg = Chunk.bytes("Hello, world!".getBytes)

  Stream.resource(open[IO]()).flatMap { serverSocket =>
    Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
      val serverAddress = new InetSocketAddress("localhost", serverPort)
      val server = serverSocket
        .reads()
        .evalMap { packet =>
          serverSocket.write(packet)
        }
        .drain
      val client = Stream.resource(open[IO]()).flatMap { clientSocket =>
        Stream(Packet(serverAddress, msg))
          .to(clientSocket.writes())
          .drain ++ Stream.eval(clientSocket.read())
      }
      server.mergeHaltBoth(client)
    }
  }

  Stream.resource(open[IO](address = new InetSocketAddress(0))).flatMap { serverSocket =>
    val socketGroup = "socketGroup"
    Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
      val serverAddress = new InetSocketAddress("localhost", serverPort)
      val server = serverSocket
        .reads()
        .evalMap { packet =>
          serverSocket.write(packet)
        }
        .drain
      val client = Stream.resource(open[IO](address = new InetSocketAddress(0))).flatMap { clientSocket =>
        Stream(Packet(serverAddress, msg))
          .to(clientSocket.writes())
          .drain ++ Stream.eval(clientSocket.read())
      }
      server.mergeHaltBoth(client)
    }
  }

}
