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
import scalaz.syntax.monad._
import scodec.bits.ByteVector

object TcpSpec extends Properties("tcp") {

  implicit val S = scalaz.concurrent.Strategy.DefaultStrategy
  implicit val AG = tcp.DefaultAsynchronousChannelGroup
  import tcp.syntax._

  def local(port: Int) =
    new InetSocketAddress("localhost", port)
  val addr = local((math.random * 2000 + 8000).toInt)

  import scala.concurrent.duration._

  def msg(s: String) = tcp.eval_(Task.delay { println(s) })

  val ids = Process.supply(0)

  def echo =
    tcp.writes_(tcp.reads(1024)) ++
    tcp.eof ++
    Process.emit(())

  def echoTest(msgs: List[String])(client: Process[tcp.Connection,String]) = {
    val server = tcp.server(addr, concurrentRequests = 1)(echo)
    val results: Process[Task,String] = server.flatMap { case responses =>
      tcp.connect(addr)(client).merge {
        responses.take(1).drain
      }
    }
    results.runLog.run.mkString ?= msgs.mkString
  }

  property("echo") = forAll { (msgs0: List[String]) =>
    val msgs = ("woot" :: msgs0).map(_ + "!") // ensure nonempty
    val out = Process(msgs: _*).pipe(text.utf8Encode)
    echoTest(msgs) {
      tcp.lastWrites_(out) ++
      tcp.reads(1024).pipe(text.utf8Decode)
    } &&
    echoTest(msgs) {
      tcp.lastWrites(out).tee(tcp.reads(1024))(tee.drainL)
         .pipe(text.utf8Decode)
    } && {
      // NB: this version occasionally causes problems on mac
      // the AsynchronousSocketChannel.close operation hangs indefinitely,
      // even though all read/write operations have completed on the socket
      // This appears to be a JDK bug
      if (System.getProperty("os.name").contains("Mac")) true
      else echoTest(msgs) {
        tcp.lastWrites_(out) merge tcp.reads(1024).pipe(text.utf8Decode)
      }
    }
  }

  property("pingpong") = forAll { (b: Byte) =>
    // repeatedly send a singe Byte back and forth
    val rd1 = tcp.read(1)
    def pong =
      rd1.repeat.take(100).takeWhile(_.isDefined).pipe(process1.stripNone).flatMap { tcp.write(_) }.drain ++
      emit(())

    val ping =
      (tcp.write_(ByteVector(b)) ++ rd1.pipe(process1.stripNone)).repeat.take(100)

    val server = tcp.server(addr, concurrentRequests = 1)(pong)
    val results: Process[Task,ByteVector] = server.flatMap { case responses =>
      tcp.connect(addr)(ping).merge {
        responses.take(1).drain
      }
    }
    val bytes: ByteVector = results.runLog.run.foldLeft(ByteVector.empty)(_ ++ _)
    bytes ?= ByteVector.fill(100)(b)
  }

  include { new Properties("chat") {
    val addr = local((math.random * 10000).toInt + 10000)
    val E = java.util.concurrent.Executors.newCachedThreadPool
    val S2 = Strategy.Executor(E)

    lazy val server = {
      val topic = async.topic[String]()
      val chat =
        tcp.reads(1024).pipe(text.utf8Decode).to(topic.publish).wye {
          tcp.writes(tcp.lift(topic.subscribe.pipe(text.utf8Encode)))
        } (wye.mergeHaltBoth)
      tcp.server(addr, concurrentRequests = 1)(chat) join     // TODO this should eventually go back to "not 1". frankly, I don't know how this is working at all even now
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
