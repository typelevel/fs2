package fs2.io.tcp

import java.net.{Inet4Address, InetSocketAddress}
import java.util.concurrent.locks.{ReentrantLock, ReentrantReadWriteLock}

import fs2.Stream
import fs2.Pull
import fs2.util.Task
import org.scalacheck.{Prop, Properties}
import org.scalacheck.Prop._


import fs2.TestUtil._
import fs2.io.TestUtil._
import fs2.io._
import fs2.concurrent
import scodec.bits.ByteVector


object SocketSpec extends Properties("tcp.socket") {

  // pull on socket echoing what it receives
  // finally it emits size of data echoed
  def echoPull(socket:Socket[Task]):Pull[Task,Nothing,Int] = {
    def go(acc:Int):Pull[Task,Nothing,Int] = {
      println(("SERVER AWITING BYTES TO READ", acc, socket))
      for {
        available <- socket.available(1024,None)
        _ = println((" ON SERVER", available))
        size <- available match {
          case None => Pull.pure(acc)
          case Some(bytes) => Pull.eval(socket.write(bytes,None)) >> go(acc + available.map(_.size).getOrElse(0))
        }
      } yield size
    }
    go(0)
  }

  // Pull that writes and then reads what was written
  def writeAndRead(socket:Socket[Task])(data:ByteVector):Pull[Task,Nothing,ByteVector] = {
    if (data.isEmpty) {
      for {
        _ <- Pull.eval(socket.close.map(_ => println(("SOCKET FORCED CLOSE, EMPTY"))))
      } yield (ByteVector.empty)
    }
    else {
      for {
        _ <- Pull.eval(socket.write(data))
        _ = println(("CLIENT WROTE", data))
        read <- socket.readOnce(data.size)
        _ = println(("CLIENT READ", data))
        addr <- Pull.eval(socket.localAddress)
        _ <- Pull.eval(socket.close)
      } yield read
    }
  }



  // simple server that echoes what it receives and then terminates
  // every connection is open until the remote hangs
  def echoServer(address:InetSocketAddress): Stream[Task,Stream[Task, Int]] = {
    implicit val name = GroupName("server")
    tcp.server[Task](address) map { pull =>
      (pull flatMap echoPull).output.run
    }
  }

  // Client that will send `source` and at the same time emits received bytes
  // Note that this first send bytes the then reads all bytes sent back and repeats until `source` is nonEmpty.
  // Will terminate when server terminates.
  def requestReplyClient(address:InetSocketAddress)(source:Stream[Task,ByteVector]):Stream[Task,ByteVector] = {
    implicit val name = GroupName("client")
    tcp.client[Task](address) flatMap { (socket:Socket[Task]) =>
      source traversePull { bs =>
        writeAndRead(socket)(bs) .output
      }
    } run
  }

//  property("echo.request.and.terminate") = protect { acquireLock {
//    val size = 15000
//    val content = ByteVector.fill(size)(0xaa)
//    val source = Stream(content)
//    val server = concurrent.join(Int.MaxValue)(echoServer(localBindAddress))
//    val client = requestReplyClient(localBindAddress)(source)
//
//    (server either client)
//    .take(2).runLog.run.run.toSet ?= Set(Left(size), Right(content))
//  }}

  property("echo.10k.requests") = protect { acquireLock {
    try {
      val size = 10
      val content = ByteVector.fill(size)(0xaa)
      val source = Stream(content)
      val server = concurrent.join(Int.MaxValue)(echoServer(localBindAddress))
      val client = requestReplyClient(localBindAddress)(source)
      val clients = concurrent.join[Task, ByteVector](1)(Stream.range(0, 1).map{r=> println(("XXXR RUNNGING CLIENT", r)); client})

      (server either clients)
      .take(100)
      .runLog.run.run ?= Vector()
    } catch {
      case t :Throwable => t.printStackTrace(); throw t
    }
  }}

}
