package scalaz.stream

import Cause._
import Process._
import java.net.InetSocketAddress
import org.scalacheck.Prop._
import org.scalacheck.Properties
import scala.concurrent.SyncVar
import scala.util.Random
import scalaz.-\/
import scalaz.\/
import scalaz.\/-
import scalaz.concurrent.Task
import scalaz.stream.Process.Halt
import scalaz.stream.ReceiveY._
import scodec.bits.ByteVector

object SocketSpec extends Properties("socket") {

  implicit val S = scalaz.concurrent.Strategy.DefaultStrategy
  implicit val AG = socket.DefaultAsynchronousChannelGroup
  import socket.syntax._

  val addr = local(11100)

  def local(port: Int) =
    new InetSocketAddress("localhost", port)

  import scala.concurrent.duration._

  def echo =
    socket.writes_(socket.reads(1024, allowPeerClosed = true)) ++
    socket.eof
    Process.emit(())

  property("echo-server") = forAll { (msgs: List[String]) =>
    val server = socket.server(addr, concurrentRequests = 1)(echo)
    val link = async.signal[Boolean]; link.set(false).run

    link.discrete.wye(server)(wye.interrupt)
        .run
        .runAsync { _.fold(e => throw e, identity) }

    Thread.sleep(50)

    val client = {
      val out = Process(msgs: _*).pipe(text.utf8Encode)
      socket.connect(addr) {
        socket.lastWrites_(out).merge(socket.reads(1024)).pipe(text.utf8Decode)
      }
    }
    try client.runLog.run.mkString ?= msgs.mkString
    finally { println("."); link.set(true).run }
  }
}

object SocketExample extends App {
  import socket.Socket

  implicit val AG = socket.DefaultAsynchronousChannelGroup

  val addr = local(11100)

  def local(port: Int) =
    new InetSocketAddress("localhost", port)

  val ids = Process.supply(0)

  // individual connection handler
  val echo: Process[Socket,Long] = socket.stamp(ids) flatMap { id =>
    socket.eval_(Task.delay { println (id.toString + "] server accepted connection") }) ++
    socket.reads(1024, allowPeerClosed = true).flatMap { bytes =>
      println(s"[$id] server got: $bytes")
      socket.write_(bytes)
    } ++
    socket.eof ++
    socket.eval_(Task.delay { println(s"[$id] server closed output") }) ++
    Process.emit(id) // emit a single value at the end of each request
  }

  val server: Process[Task, Throwable \/ Long] =
    socket.server(addr, concurrentRequests = 2)(echo)

  val greetings = Process("hi", "bye", "w00t!!!1!")

  val link = async.signal[Boolean]; link.set(false).run

  link.discrete.wye(server)(wye.interrupt) // kill the server when `link` becomes `true`
    .run
    .runAsync { e => println("server shutting down: " + e) }

  import socket.syntax._

  Thread.sleep(200)
  import scala.concurrent.duration._
  import scalaz.concurrent.Strategy.{DefaultStrategy => S}

  val client: Process[Task,String] = {
    val logic =
      socket.eval_(Task.delay { println ("[client] running") }) ++ {
        val writes: Process[Socket,Nothing] =
          socket.lastWrites(greetings.pipe(text.utf8Encode))
                .map(_ => println("[client] wrote bytes"))
                .drain
                .onComplete(socket.eval_(Task.delay(println("[client] finished writing bytes"))))
        val reads =
          socket.reads(1024, timeout = Some(5 seconds))
                .onComplete(socket.eval_(Task.delay(println("client done reading!"))))
        writes.merge(reads)(S)
      }
    socket.connect(addr)(logic).pipe(text.utf8Decode)
  }

  println {
    try client.runLog.run
    finally link.set(true).run
  }
}
