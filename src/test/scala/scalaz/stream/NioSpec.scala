package scalaz.stream

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
import scalaz.stream.ReceiveY._


object EchoServer {

  import NioSpec._

  def apply(address: InetSocketAddress) = {
    def echo: Process1W[ByteData, ByteData, ByteData] = {
      receive1[Array[Byte], ByteData \/ ByteData]({
        i => emitSeq(Seq(\/-(i), -\/(i))) fby echo
      })
    }




    (for {
      connections <- nio.server(address)
      e <- connections
      _ <- emit(println("Accepted"))
      rcvd <- e.throughW(echo)
      _ <- emit(println("received", rcvd.size))
    } yield (rcvd))
  }


  def server2(address:InetSocketAddress) = {
    val (q, src) = async.queue[Array[Byte]]

    for {
      connections <- nio.server(address)
      e : Exchange[Array[Byte],Array[Byte]] <- connections
      unit <- eval_(Task.fork((e.read.map{v=> println("SERVER GOT", v.take(1).toList, v.size);v} to q.toSink()).run)) merge eval_(Task.fork((src.map{v=> println("SERVER ECHOED",v.take(1).toList, v.size);v} to e.write).run))
    } yield (unit)


  }



}


object NioSpec extends Properties("nio") {

  val local = new InetSocketAddress("127.0.0.1", 3476)

  type ByteData = Array[Byte]

  implicit val AG = nio.DefaultAsynchronousChannelGroup


  property("connect-echo-done") = secure {
    val size : Int = 2000000

    val stop = async.signal[Boolean]
    stop.set(false).run
    val received = new SyncVar[Throwable \/ Unit]
    val server = EchoServer.apply(local)
    Task.fork(server.run).runAsync(received.put)

    Thread.sleep(500) //give chance server to bind correctly

    val array1 = Array.fill[Byte](size)(1)
    val array2 = Array.fill[Byte](size)(2)
    //Random.nextBytes(array)

    def sendRcvEcho(read:Int): WyeW[ByteData, ByteData, ByteData, ByteData] = {
      println("Read so far", read)
      awaitBoth[ByteData, ByteData].flatMap {
        case ReceiveL(fromServer)
          if (fromServer.size + read >= size*2) => println("#####>>>>",fromServer.take(1).toList, read, fromServer.size); emitO(fromServer)
        case ReceiveL(fromServer) => println("S>>>>", fromServer.take(1).toList, fromServer.size, read); emitO(fromServer) fby sendRcvEcho(read + fromServer.size)
        case ReceiveR(toServer)   => println("S<<<<", toServer.take(1).toList, toServer.size); emitW(toServer) fby sendRcvEcho(read)
        case HaltR(rsn)           => sendRcvEcho(read)
        case HaltL(rsn)           => Halt(rsn)
      }
    }

    val client =
      for {
        e: Exchange[ByteData, ByteData] <- nio.connect(local)
        got <- e.readAndWrite(Process(array1,array2).toSource)(sendRcvEcho(0))
      } yield (got)

    val clientCollected = new SyncVar[Throwable \/ IndexedSeq[ByteData]]

    Task.fork(client.map{v => println("echoed", v.size);v}.runLog).runAsync(clientCollected.put)

    clientCollected.get(3000) match {
      case Some(\/-(got)) => println("DONE_OK", got.flatten.size);   got.flatten.toList == (array1 ++ array2).toList
      case other => println("!!!!!!", other) ; false
    }

  }


}
