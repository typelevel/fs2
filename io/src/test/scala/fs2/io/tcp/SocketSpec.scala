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
      for {
        available <- socket.available(1024,None)
        _ = println((" ON SERVER", available))
        size <- available match {
          case None => Pull.pure(acc)
          case Some(bytes) => Pull.eval(socket.write(bytes,None)) >> go(acc + available.size)
        }
      } yield size
    }
    go(0)
  }

  // Pull that writes and then reads what was written
  def writeAndRead(socket:Socket[Task])(data:ByteVector):Pull[Task,Nothing,ByteVector] = {
    if (data.isEmpty) Pull.pure(ByteVector.empty)
    else {
      for {
        _ <- Pull.eval(socket.write(data))
        _ = println(("CLIENT WROTE", data))
        read <- socket.readOnce(data.size)
        _ = println(("CLIENT READ", data))
      } yield read
    }
  }



  // simple server that echoes what it receives and then terminates
  // every connection is open until the remote hangs
  def echoServer(address:InetSocketAddress): Stream[Task,Stream[Task, Int]] = {
    tcp.server[Task](address) map { pull =>
      (pull flatMap echoPull).run
    }
  }

  // Client that will send `source` and at the same time emits received bytes
  // Note that this first send bytes the then reads all bytes sent back and repeats until `source` is nonEmpty.
  // Will terminate when server terminates.
  def requestReplyClient(address:InetSocketAddress)(source:Stream[Task,ByteVector]):Stream[Task,ByteVector] = {
    tcp.client[Task](address).run flatMap { (socket:Socket[Task]) =>
      println(("SOCKET CLIENT", socket))
      source map writeAndRead(socket) flatMap { pull => pull.run }
    }
  }

  property("echo") = protect { acquireLock {
    println("ECHO STARTED")
    val source = Stream(ByteVector(1,2,3,4))
    val server = concurrent.join(Int.MaxValue)(echoServer(localBindAddress))
    //val client = requestReplyClient(localBindAddress)(source)

    val client = requestReplyClient(new InetSocketAddress("www.spinoco.com", 443))(source)
    client.runLog.run.run ?= Vector()
    //(server either client).take(2).runLog.run.run ?= Vector()
  }}

}
