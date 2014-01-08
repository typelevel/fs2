package scalaz.stream

import Process._
import java.net.InetSocketAddress
import org.scalacheck.Prop._
import org.scalacheck.Properties
import scala.Some
import scala.concurrent.SyncVar
import scala.util.Random
import scalaz.-\/
import scalaz.\/
import scalaz.\/-
import scalaz.concurrent.Task
import scalaz.stream.Process.Halt
import scalaz.stream.ReceiveY.HaltL
import scalaz.stream.ReceiveY.HaltR
import scalaz.stream.ReceiveY.ReceiveL
import scalaz.stream.ReceiveY.ReceiveR


object NioServer {



  def apply(address: InetSocketAddress, w: Writer1[Bytes, Bytes, Bytes]): Process[Task, Bytes] = {
    val srv =
      nio.server(address).map {
        _.flatMap {
          ex => ex.readThrough(w).runReceive
        }
      }

    merge.mergeN(srv)
  }

  def echo(address: InetSocketAddress): Process[Task, Bytes] = {
    def echoAll: Writer1[Bytes, Bytes, Bytes] = {
      receive1[Bytes, Bytes \/ Bytes]({
        i => emitSeq(Seq(\/-(i), -\/(i))) fby echoAll
      })
    }

    apply(address, echoAll)

  }

  def limit(address: InetSocketAddress, size:Int) :  Process[Task, Bytes] = {

    def remaining(sz:Int): Writer1[Bytes, Bytes, Bytes] = {
      receive1[Bytes, Bytes \/ Bytes]({
        i => 
          val toEcho = i.take(sz)
          if (sz - toEcho.size <= 0) emitSeq(Seq(\/-(toEcho), -\/(toEcho))) fby halt
          else  emitSeq(Seq(\/-(toEcho), -\/(toEcho))) fby remaining(sz -toEcho.size)
      })
    }
    
    apply(address,remaining(size))

  }

}

object NioClient {



  def echo(address: InetSocketAddress, data: Bytes): Process[Task, Bytes] = {

    def echoSent: WyeW[Bytes, Bytes, Bytes, Bytes] = {
      def go(collected: Int): WyeW[Bytes, Bytes, Bytes, Bytes] = {
        receiveBoth {
          case ReceiveL(rcvd) =>
            emitO(rcvd) fby
              (if (collected + rcvd.size >= data.size) halt
              else go(collected + rcvd.size))
          case ReceiveR(data) => tell(data) fby go(collected)
          case HaltL(rsn)     => Halt(rsn)
          case HaltR(_)       => go(collected)
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

  val local = new InetSocketAddress("127.0.0.1", 3476)

  implicit val AG = nio.DefaultAsynchronousChannelGroup

  //  property("loop-server") = secure {
  //    NioServer.limit(local,3).run.run
  //    false
  //  }


    //simple connect to server send size of bytes and got back what was sent
    property("connect-echo-done") = secure {
    val size: Int = 500000
    val array1 = Array.fill[Byte](size)(1)
    Random.nextBytes(array1)

    val stop = async.signal[Boolean]
    stop.set(false).run

    val serverGot = new SyncVar[Throwable \/ IndexedSeq[Byte]]
    stop.discrete.wye(NioServer.echo(local))(wye.interrupt)
    .runLog.map(_.map(_.toSeq).flatten).runAsync(serverGot.put)

    Thread.sleep(300)

    val clientGot =
      NioClient.echo(local, Bytes.of(array1)).runLog.timed(3000).run.map(_.toSeq).flatten
    stop.set(true).run

    (serverGot.get(5000) == Some(\/-(clientGot))) :| s"Server and client got same data" &&
      (clientGot == array1.toSeq) :| "client got what it sent"

  }


    property("connect-server-terminates") = secure {

      val max: Int = 50
      val size: Int = 5000
      val array1 = Array.fill[Byte](size)(1)
      Random.nextBytes(array1)

      val stop = async.signal[Boolean]
      stop.set(false).run

      val serverGot = new SyncVar[Throwable \/ IndexedSeq[Byte]]
      stop.discrete.wye(NioServer.limit(local,max))(wye.interrupt)
      .runLog.map(_.map(_.toSeq).flatten).runAsync(serverGot.put)

      Thread.sleep(300)

      val clientGot =
        NioClient.echo(local, Bytes.of(array1)).runLog.run.map(_.toSeq).flatten
      stop.set(true).run

      (serverGot.get(30000) == Some(\/-(clientGot))) :| s"Server and client got same data" &&
        (clientGot == array1.toSeq.take(max)) :| "client got bytes before server closed connection"

    }


  // connects large number of client to server, each sending up to `size` data and server replying them back
  // at the end server shall have callected all data from all clients and all clients shall get echoed back
  // what they have sent to server
  property("connect-server-many") = secure {

    val count: Int = 100
    val size: Int = 10000
    val array1 = Array.fill[Byte](size)(1)
    Random.nextBytes(array1)

    val stop = async.signal[Boolean]
    stop.set(false).run


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

    Thread.sleep(300)

    val client = NioClient.echo(local, Bytes.of(array1)).attempt().flatMap {
      case \/-(v) => emit(v)
      case -\/(err) => halt
    }

    val clients =
      merge.mergeN(Process.range(0,count).map(_=>client)).bufferAll

    val clientGot = new SyncVar[Throwable \/ Seq[Seq[Byte]]]

    Task(
      (clients onComplete eval_(Task.delay(stop.set(true).run)))
      .runLast.map(v=>v.map(_.toSeq).toSeq)
      .runAsync(clientGot.put)
    ).run

    serverGot.get(3000)
    clientGot.get(3000)

    stop.set(true).run

    (serverGot.isSet && clientGot.isSet) :| "Server and client terminated" &&
      (serverGot.get.isRight && clientGot.get.isRight) :| s"Server and client terminate w/o failure: s=${serverGot.get(0)}, c=${clientGot.get(0)}" &&
    (serverGot.get(0) == clientGot.get(0)) :| s"Server and client got same data"

  }


}
