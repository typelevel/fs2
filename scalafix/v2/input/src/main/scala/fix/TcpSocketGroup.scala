/*
rule = v2
 */
package fix

import java.net.InetSocketAddress
import java.net.InetAddress
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.Executors

import cats.effect.{ContextShift, IO}
import cats.effect.implicits._
import cats.effect.concurrent.Deferred
import fs2.{Chunk, Stream}
import fs2.io.tcp.Socket._

import scala.concurrent.ExecutionContext

object TcpSocketGroup {

  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global

  implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(executionContext)

  implicit val tcpACG: AsynchronousChannelGroup = AsynchronousChannelProvider
    .provider()
    .openAsynchronousChannelGroup(8, Executors.defaultThreadFactory())

  val message = Chunk.bytes("fs2.rocks".getBytes)
  val clientCount = 20

  val localBindAddress =
    Deferred[IO, InetSocketAddress].unsafeRunSync()

  val echoServer: Stream[IO, Unit] = {
    serverWithLocalAddress[IO](new InetSocketAddress(InetAddress.getByName(null), 0)).flatMap {
      case Left(local) => Stream.eval_(localBindAddress.complete(local))
      case Right(s) =>
        Stream.resource(s).map { socket =>
          socket
            .reads(1024)
            .to(socket.writes())
            .onFinalize(socket.endOfOutput)
        }
    }.parJoinUnbounded
  }

  val clients: Stream[IO, Array[Byte]] = {
    Stream
      .range(0, clientCount)
      .map { idx =>
        Stream.eval(localBindAddress.get).flatMap { local =>
          Stream.resource(client[IO](local)).flatMap { socket =>
            Stream
              .chunk(message)
              .to(socket.writes())
              .drain
              .onFinalize(socket.endOfOutput) ++
              socket.reads(1024, None).chunks.map(_.toArray)
          }
        }
      }
      .parJoin(10)
  }

}
