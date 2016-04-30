package fs2.io.tcp

import java.nio.channels.AsynchronousChannelGroup

import fs2._
import fs2.util.Task

import fs2.io.TestUtil._
import fs2.Stream._


object SocketSpec {
  implicit val tcpACG : AsynchronousChannelGroup = namedACG("tcp")
  println(s">>> BINDING TO:  $localBindAddress")
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
    "echo.requests" in { acquireLock {

        val message = Chunk.bytes("fs2.rocks".getBytes)
        val clientCount = 10000

        val echoServer: Stream[Task, Unit] = {
          val ps =
            server[Task](localBindAddress)
            .map {
              _.flatMap { (socket: Socket[Task]) =>
                socket.writes(
                  socket.reads(1024)
                )
                .onFinalize(socket.endOfOutput)
              }
            }

          concurrent.join(Int.MaxValue)(ps)
        }

        val clients: Stream[Task, Array[Byte]] = {
          val pc: Stream[Task, Stream[Task, Array[Byte]]] =
            Stream.range[Task](0, clientCount).map { idx =>
              client[Task](localBindAddress).flatMap { socket =>
                socket.writes(emit(message), None).drain.onFinalize(socket.endOfOutput) ++
                  socket.reads(1024, None).map(_.toArray)
              }
            }

          concurrent.join(10)(pc)
        }

        val result =
          concurrent.join(2)(Stream(
            echoServer.drain
            , clients
          ))
          .take(clientCount).runLog.run.unsafeRun


        (result.size == clientCount) &&
          (result.map {  new String(_) }.toSet == Set("fs2.rocks"))
      }}
  }




}
