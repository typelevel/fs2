package scalaz.stream


import Cause._
import Process._
import java.net.InetSocketAddress
import java.util.concurrent.ScheduledExecutorService
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


object NioServer {

  implicit val AG = nio.DefaultAsynchronousChannelGroup

  def apply(address: InetSocketAddress, w: Writer1[ByteVector, ByteVector, ByteVector]): Process[Task, ByteVector] = {
    val srv =
      nio.server(address).map {
        _.flatMap {
          ex => ex.readThrough(w).run()
        }
      }

    merge.mergeN(srv)
  }

  def echo(address: InetSocketAddress): Process[Task, ByteVector] = {
    def echoAll: Writer1[ByteVector, ByteVector, ByteVector] = {
      receive1[ByteVector, ByteVector \/ ByteVector]({
        i => emitAll(Seq(\/-(i), -\/(i))) ++ echoAll
      })
    }

    apply(address, echoAll)

  }

  def limit(address: InetSocketAddress, size:Int) :  Process[Task, ByteVector] = {

    def remaining(sz:Int): Writer1[ByteVector, ByteVector, ByteVector] = {
      receive1[ByteVector, ByteVector \/ ByteVector]({
        i =>
          val toEcho = i.take(sz)
          if (sz - toEcho.size <= 0) emitAll(Seq(\/-(toEcho), -\/(toEcho))) ++ halt
          else  emitAll(Seq(\/-(toEcho), -\/(toEcho))) ++ remaining(sz -toEcho.size)
      })
    }

    apply(address,remaining(size))

  }

}

object NioClient {

  implicit val AG = nio.DefaultAsynchronousChannelGroup

  def echo(address: InetSocketAddress, data: ByteVector): Process[Task, ByteVector] = {

    def echoSent: WyeW[ByteVector, ByteVector, ByteVector, ByteVector] = {
      def go(collected: Int): WyeW[ByteVector, ByteVector, ByteVector, ByteVector] = {
        wye.receiveBoth {
          case ReceiveL(rcvd) =>
            emitO(rcvd) ++
              (if (collected + rcvd.size >= data.size) halt
              else go(collected + rcvd.size))
          case ReceiveR(data) => tell(data) ++ go(collected)
          case HaltL(rsn)     => Halt(rsn)
          case HaltR(End)       => go(collected)
          case HaltR(rsn)  => Halt(rsn)
        }
      }

      go(0)
    }


    for {
      ex <- nio.connect(address)
      rslt <- ex.wye(echoSent).run(emit(data))
    } yield {
      rslt
    }
  }

}


object NioSpec extends Properties("nio") {

  def localAddress(port:Int) = new InetSocketAddress("127.0.0.1", port)

  implicit val AG = nio.DefaultAsynchronousChannelGroup
  implicit val S: ScheduledExecutorService = DefaultScheduler

  //  property("loop-server") = secure {
  //    NioServer.limit(local,3).run.run
  //    false
  //  }


  //simple connect to server send size of bytes and got back what was sent
  property("connect-echo-done") = secure {
    val local = localAddress(11100)
    val size: Int = 500000
    val array1 = Array.fill[Byte](size)(1)
    Random.nextBytes(array1)

    val stop = async.signalOf(false)

    val serverGot = new SyncVar[Throwable \/ IndexedSeq[Byte]]
    stop.discrete.wye(NioServer.echo(local))(wye.interrupt)
    .runLog.map(_.map(_.toSeq).flatten).runAsync(serverGot.put)

    Thread.sleep(300)

    val clientGot =
      NioClient.echo(local, ByteVector(array1)).runLog.timed(3000).run.map(_.toSeq).flatten
    stop.set(true).run

    (serverGot.get(5000) == Some(\/-(clientGot))) :| s"Server and client got same data" &&
      (clientGot == array1.toSeq) :| "client got what it sent"

  }


  property("connect-server-terminates") = secure {
    val local = localAddress(11101)
    val max: Int = 50
    val size: Int = 5000
    val array1 = Array.fill[Byte](size)(1)
    Random.nextBytes(array1)

    val stop = async.signalOf(false)

    val serverGot = new SyncVar[Throwable \/ IndexedSeq[Byte]]
    stop.discrete.wye(NioServer.limit(local,max))(wye.interrupt)
    .runLog.map(_.map(_.toSeq).flatten).runAsync(serverGot.put)

    Thread.sleep(300)

    val clientGot =
      NioClient.echo(local, ByteVector(array1)).runLog.run.map(_.toSeq).flatten
    stop.set(true).run

    (serverGot.get(30000) == Some(\/-(clientGot))) :| s"Server and client got same data" &&
      (clientGot == array1.toSeq.take(max)) :| "client got bytes before server closed connection"

  }


  // connects large number of client to server, each sending up to `size` data and server replying them back
  // at the end server shall have collected all data from all clients and all clients shall get echoed back
  // what they have sent to server
  property("connect-server-many") = secure {
    val local = localAddress(11102)
    val count: Int = 100
    val size: Int = 10000
    val array1 = Array.fill[Byte](size)(1)
    Random.nextBytes(array1)

    val stop = async.signalOf(false)


    val serverGot = new SyncVar[Throwable \/ Seq[Seq[Byte]]]

    val server =
      stop.discrete
      .wye(
          NioServer.echo(local)
        )(wye.interrupt)
      .bufferAll


    Task(
      server
      .runLast.map{_.map(_.toSeq).toSeq}
      .runAsync(serverGot.put)
    ).run

    Thread.sleep(1000)

    val client = NioClient.echo(local, ByteVector(array1)).attempt().flatMap {
      case \/-(v) => emit(v)
      case -\/(err) => halt
    }

    val clients =
      merge.mergeN(Process.range(0,count).map(_=>client)).bufferAll

    val clientGot = new SyncVar[Throwable \/ Seq[Seq[Byte]]]

    Task(
      (clients onComplete eval_(stop.set(true)))
      .runLast.map(v=>v.map(_.toSeq).toSeq)
      .runAsync(clientGot.put)
    ).run

    clientGot.get(6000)
    serverGot.get(6000)

    stop.set(true).run

    (serverGot.isSet && clientGot.isSet) :| "Server and client terminated" &&
      (serverGot.get(0).exists(_.isRight) && clientGot.get(0).exists(_.isRight)) :| s"Server and client terminate w/o failure: s=${serverGot.get(0)}, c=${clientGot.get(0)}" &&
      (serverGot.get(0) == clientGot.get(0)) :| s"Server and client got same data"

  }


}

