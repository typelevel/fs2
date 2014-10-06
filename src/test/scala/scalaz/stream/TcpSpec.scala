package scalaz.stream

import Cause._
import Process._
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import org.scalacheck.Prop._
import org.scalacheck.Properties
import scala.concurrent.SyncVar
import scala.util.Random
import scalaz.-\/
import scalaz.\/
import scalaz.\/-
import scalaz.concurrent.{Strategy,Task}
import scalaz.stream.Process.Halt
import scalaz.stream.ReceiveY._
import scodec.bits.ByteVector

object TcpSpec extends Properties("tcp") {

  implicit val S = scalaz.concurrent.Strategy.DefaultStrategy
  implicit val AG = tcp.DefaultAsynchronousChannelGroup
  import tcp.syntax._

  def local(port: Int) =
    new InetSocketAddress("localhost", port)

  import scala.concurrent.duration._

  include { new Properties("echo") {
    val addr = local((math.random * 10000).toInt + 10000)

    def echo =
      tcp.writes_(tcp.reads(1024)) ++
      tcp.eof ++
      Process.emit(())

    val server = tcp.server(addr, concurrentRequests = 1)(echo).run
    val link = async.signalOf(false)
    lazy val startServer =
      link.discrete.wye(server)(wye.interrupt)
          .run
          .runAsync { _.fold(e => throw e, identity) }
    lazy val stopServer = link.set(true).run

    property("setup") = forAll ((i: Int) => { startServer; true })

    property("echo") = forAll { (msgs0: List[String]) =>
      val msgs = msgs0.map(_ + "!") // ensure nonempty
      val client = {
        val out = Process(msgs: _*).pipe(text.utf8Encode)
        tcp.connect(addr) {
           tcp.lastWrites_(out) ++
           tcp.reads(1024).pipe(text.utf8Decode)
        }
      }
      client.runLog.run.mkString ?= msgs.mkString
    }
    property("echo2") = forAll { (msgs0: List[String]) =>
      val msgs = msgs0.map(_ + "!") // ensure nonempty
      val client = {
        val out = Process(msgs: _*).pipe(text.utf8Encode)
        tcp.connect(addr) {
          tcp.lastWrites(out).tee(tcp.reads(1024))(tee.drainL)
             .pipe(text.utf8Decode)
        }
      }
      client.runLog.run.mkString ?= msgs.mkString
    }
    property("echo3") = {
      if (System.getProperty("os.name").contains("Mac")) true
      else {
        // NB: this version occasionally causes problems on mac
        // the AsynchronousSocketChannel.close operation hangs indefinitely,
        // even though all read/write operations have completed on the socket
        // This appears to be a JDK bug
        forAll { (msgs0: List[String]) => {
          val msgs = ("woot" :: msgs0).map(_ + "!") // ensure nonempty
          val client: Process[Task,String] = {
            val out: Process[Task,ByteVector] = Process(msgs: _*).pipe(text.utf8Encode)
            val numChars = msgs.view.map(_.length).sum
            for {
              exch <- nio.connect(addr)
              s <- (out.to(exch.write).drain: Process[Task,String]).merge {
                exch.read.pipe(text.utf8Decode)
                    .zipWithScan1(0)(_.length + _)
                    .takeThrough { case (s,n) => n < numChars }
                    .map { p => p._1 : String }
              }
            } yield s
          }
          val r = client.runLog.run.mkString == msgs.mkString
          println(math.random)
          r
        }}
      }
    }
    property("echo4") = {
      if (System.getProperty("os.name").contains("Mac")) true
      else {
        // NB: this version occasionally causes problems on mac
        // the AsynchronousSocketChannel.close operation hangs indefinitely,
        // even though all read/write operations have completed on the socket
        // This appears to be a JDK bug
        forAll { (msgs0: List[String]) => {
          val msgs = ("woot" :: msgs0).map(_ + "!") // ensure nonempty
          val client = {
            val out = Process(msgs: _*).pipe(text.utf8Encode)
            val numChars = msgs.view.map(_.length).sum
            tcp.connect(addr) {
              tcp.writes(out).tee {
                tcp.reads(1024)
                   .pipe(text.utf8Decode)
                   .zipWithScan1(0)(_.length + _)
                   .takeThrough { case (s,n) => n < numChars }
                   .map { _._1 }
              } (tee.drainL)
            }
          }
          val r = client.runLog.run.mkString == msgs.mkString
          r
        }}
      }
    }
    property("echo5") = {
      if (System.getProperty("os.name").contains("Mac")) true
      else {
        // NB: this version occasionally causes problems on mac
        // the AsynchronousSocketChannel.close operation hangs indefinitely,
        // even though all read/write operations have completed on the socket
        // This appears to be a JDK bug
        forAll { (msgs0: List[String]) => {
          val msgs = ("woot" :: msgs0).map(_ + "!") // ensure nonempty
          val client: Process[Task,String] = {
            val out = Process(msgs: _*).pipe(text.utf8Encode)
            tcp.connect(addr) {
              tcp.lastWrites_(out) merge (tcp.reads(1024).pipe(text.utf8Decode))
            }
          }
          val r = client.runLog.run.mkString == msgs.mkString
          r
        }}
      }
    }

    property("teardown") = forAll ((i: Int) => { stopServer; true })
  }}

  include { new Properties("chat") {
    val addr = local((math.random * 10000).toInt + 10000)
    val E = java.util.concurrent.Executors.newCachedThreadPool
    val S2 = Strategy.Executor(E)

    lazy val server = Process.suspend {
      val topic = async.topic[String]()
      val chat =
        tcp.reads(1024).pipe(text.utf8Decode).to(topic.publish).wye {
          tcp.writes(tcp.lift(topic.subscribe.pipe(text.utf8Encode)))
        } (wye.mergeHaltBoth)
      tcp.server(addr, concurrentRequests = 50)(chat).run
    }
    lazy val link = async.signalOf(false)
    lazy val startServer =
      link.discrete.wye(server)(wye.interrupt)
          .run
          .runAsync { _.fold(e => throw e, identity) }
    lazy val stopServer = { E.shutdown(); link.set(true).run }

    property("setup") = forAll ((i: Int) => { startServer; true })
    property("go") =
      if (System.getProperty("os.name").contains("Mac")) true
      else forAll { (msgs: List[List[String]]) =>
        val clients = msgs.map { msgs =>
          tcp.connect(addr) {
            tcp.lastWrites_(Process.emitAll(msgs).pipe(text.utf8Encode)).merge {
              tcp.reads(1024).take(1).pipe(text.utf8Decode)
            }
          }
        }
        nondeterminism.njoin(10, 10)(Process.emitAll(clients))(S2).run.run
        true
      }
    property("teardown") = forAll ((i: Int) => { stopServer; true })
  }}
}
