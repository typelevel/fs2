package fix

import java.net.InetSocketAddress

import cats.effect.{ContextShift, IO}
import fs2.{Chunk, Stream}
import fs2.io.udp.{AsynchronousSocketGroup, Packet}

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

  Stream.resource(cats.effect.Blocker[IO].flatMap(blocker => fs2.io.udp.SocketGroup(blocker))).flatMap { socketGroup =>
Stream.resource(socketGroup.open[IO]()).flatMap { serverSocket =>
    Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
      val serverAddress = new InetSocketAddress("localhost", serverPort)
      val server = serverSocket
        .reads()
        .evalMap { packet =>
          serverSocket.write(packet)
        }
        .drain
      val client = Stream.resource(socketGroup.open[IO]()).flatMap { clientSocket =>
        Stream(Packet(serverAddress, msg))
          .to(clientSocket.writes())
          .drain ++ Stream.eval(clientSocket.read())
      }
      server.mergeHaltBoth(client)
    }
  }}

  Stream.resource(cats.effect.Blocker[IO].flatMap(blocker => fs2.io.udp.SocketGroup(blocker))).flatMap { socketGroup1 =>
Stream.resource(socketGroup1.open[IO](address = new InetSocketAddress(0))).flatMap { serverSocket =>
    val socketGroup = "socketGroup"
    Stream.eval(serverSocket.localAddress).map { _.getPort }.flatMap { serverPort =>
      val serverAddress = new InetSocketAddress("localhost", serverPort)
      val server = serverSocket
        .reads()
        .evalMap { packet =>
          serverSocket.write(packet)
        }
        .drain
      val client = Stream.resource(socketGroup1.open[IO](address = new InetSocketAddress(0))).flatMap { clientSocket =>
        Stream(Packet(serverAddress, msg))
          .to(clientSocket.writes())
          .drain ++ Stream.eval(clientSocket.read())
      }
      server.mergeHaltBoth(client)
    }
  }}

}
