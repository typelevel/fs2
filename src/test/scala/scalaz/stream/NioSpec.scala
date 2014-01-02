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

  import NioSpec._

  def apply(address: InetSocketAddress, w: Writer1[ByteData, ByteData, ByteData]): Process[Task, ByteData] = {
    val srv =
      for {
        ep <- nio.server(address)
        ex <- ep.once
      } yield {
        ex.readThrough(w).runReceive
      }

    merge.mergeN(srv)
  }

  def echo(address: InetSocketAddress): Process[Task, ByteData] = {
    def echoAll: Writer1[ByteData, ByteData, ByteData] = {
      receive1[Array[Byte], ByteData \/ ByteData]({
        i => emitSeq(Seq(\/-(i), -\/(i))) fby echoAll
      })
    }

    apply(address, echoAll)

  }

  /*def limit(address: InetSocketAddress, size:Int) :  Process[Task, ByteData] = {

    def remianing(sz:Int): Writer1[ByteData, ByteData, ByteData] = {
      receive1[Array[Byte], ByteData \/ ByteData]({
        i => 
          val toEcho = i.take(sz)
          
        
          emitSeq(Seq(\/-(i), -\/(i))) fby echoAll
      })
    }
    
    apply(address,remianing(size))

  }
*/
}

object NioClient {

  import NioSpec._

  def echo(address: InetSocketAddress, data: ByteData): Process[Task, ByteData] = {

    def echoSent: WyeW[ByteData, ByteData, ByteData, ByteData] = {
      def go(collected: Int): WyeW[ByteData, ByteData, ByteData, ByteData] = {
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

  type ByteData = Array[Byte]

  implicit val AG = nio.DefaultAsynchronousChannelGroup

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
      NioClient.echo(local, array1).runLog.run.map(_.toSeq).flatten
    stop.set(true).run

    (serverGot.get(30000) == Some(\/-(clientGot))) :| s"Server and client got same data" &&
      (clientGot == array1.toSeq) :| "client got what it sent"

  }


}
