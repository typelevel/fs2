package fs2.io.tcp

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import fs2._
import fs2.util.Task

import fs2.io.TestUtil._
import fs2.Stream._


object SocketSpec {
  implicit val tcpACG : AsynchronousChannelGroup = namedACG("tcp")
}

/**
  * Created by pach on 10/04/16.
  */
class SocketSpec extends Fs2Spec {

  import SocketSpec.tcpACG


  "tcp" - {

    // spawns echo server, takes whatever client sends and echoes it back
    // up to 10 clients concurrently (10k total) send message and awaits echo of it
    // success is that all clients got what they have sent
    "echo.requests" in {

        val message = Chunk.bytes("fs2.rocks".getBytes)
        val clientCount = 5000

        val localBindAddress = Task.ref[InetSocketAddress].unsafeRun

        val echoServer: Stream[Task, Unit] = {
          val ps =
            serverWithLocalAddress[Task](new InetSocketAddress(0))
            .flatMap {
              case Left(local) => Stream.eval_(localBindAddress.set(Task.now(local)))
              case Right(s) =>
                Stream.emit(s.flatMap { (socket: Socket[Task]) =>
                  socket.reads(1024).to(socket.writes()).onFinalize(socket.endOfOutput)
                })
            }

          concurrent.join(Int.MaxValue)(ps)
        }

        val clients: Stream[Task, Array[Byte]] = {
          val pc: Stream[Task, Stream[Task, Array[Byte]]] =
            Stream.range[Task](0, clientCount).map { idx =>
              Stream.eval(localBindAddress.get).flatMap { local =>
                client[Task](local).flatMap { socket =>
                  Stream.chunk(message).to(socket.writes()).drain.onFinalize(socket.endOfOutput) ++
                    socket.reads(1024, None).chunks.map(_.toArray)
                }
              }
            }

          concurrent.join(10)(pc)
        }

        val result =
          concurrent.join(2)(Stream(
            echoServer.drain
            , clients
          ))
          .take(clientCount).runLog.unsafeRun


        (result.size shouldBe clientCount)
          (result.map {  new String(_) }.toSet shouldBe Set("fs2.rocks"))
      }
  }




}
