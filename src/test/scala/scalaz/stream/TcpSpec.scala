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
import scalaz.concurrent.Task
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
      tcp.writes_(tcp.reads(1024, allowPeerClosed = true)) ++
      tcp.eof ++
      Process.emit(())

    val server = tcp.server(addr, concurrentRequests = 1)(echo).run
    val link = async.signal[Boolean]; link.set(false).run
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
        forAll { (msgs0: List[String]) =>
          val msgs = msgs0.map(_ + "!") // ensure nonempty
          val client = {
            val out = Process(msgs: _*).pipe(text.utf8Encode)
            tcp.connect(addr) {
              tcp.lastWrites_(out).merge(tcp.reads(1024))
                 .pipe(text.utf8Decode)
            }
          }
          client.runLog.run.mkString ?= msgs.mkString
        }
      }
    }

    property("teardown") = forAll ((i: Int) => { stopServer; true })
  }}

  include { new Properties("chat") {
    val addr = local((math.random * 10000).toInt + 10000)

    lazy val server = Process.suspend {
      val topic = async.topic[String]()
      val chat =
        tcp.reads(1024).pipe(text.utf8Decode).to(topic.publish).wye {
          tcp.writes(tcp.lift(topic.subscribe.pipe(text.utf8Encode)))
        } (wye.mergeHaltBoth) // kill the connection when client stops listening or writing
      tcp.server(addr, concurrentRequests = 50)(chat).run
    }
    lazy val link = async.signal[Boolean]; link.set(false).run
    lazy val startServer =
      link.discrete.wye(server)(wye.interrupt)
          .run
          .runAsync { _.fold(e => throw e, identity) }
    lazy val stopServer = link.set(true).run

    property("setup") = forAll ((i: Int) => { startServer; true })
    property("go") = forAll { (msgs: List[List[String]]) =>
      true
    }
    property("teardown") = forAll ((i: Int) => { stopServer; true })
  }}

}
