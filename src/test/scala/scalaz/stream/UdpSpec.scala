package scalaz.stream

import java.net.{InetSocketAddress, InetAddress, DatagramSocket,MulticastSocket, DatagramPacket}
import org.scalacheck._
import Prop._
import scodec.bits.ByteVector
import java.util.concurrent.CountDownLatch
import scalaz.concurrent.Task
import scalaz.stream.udp.Connection

class UdpSpec extends Properties("udp") {

  implicit val n = Arbitrary(Gen.choose(2000,10000))

  def test(msgs0: List[String], port: Int, inetAddress: InetAddress,  serverFunc: Process[Connection,ByteVector] => Process[Task,ByteVector]) = {
    val msgs = msgs0.map(s => ByteVector.view(s.getBytes("UTF8"))).toSet

    val addr = new InetSocketAddress(inetAddress, port)

    val latch = new CountDownLatch(1) // use to make sure server is listening when we start sending packets

    val server: Process[Task, ByteVector] = serverFunc{
      udp.eval_(Task.delay { latch.countDown }) ++
      udp.receives(1024).take(msgs0.length - 4).map(_.bytes) // some packets in theory could be dropped
    }

    val client: Process[Task,Nothing] = udp.listen(port+1) {
      udp.eval_(Task.delay { latch.await }) ++
      udp.sends_(to = addr, Process.emitAll(msgs.toList))
    }
    
    val received: Set[ByteVector] = server.merge(client).runLog.run.toSet
    val result = (received intersect msgs).size >= msgs.size - 5
    if (msgs.size <= 4) classify(true, "empty")(result)
    else if (msgs.size < 15) classify(true, "small")(result)
    else classify(true, "large")(result)
  }

  property("multicast") = forAll { (msgs0: List[String], port: Int) =>
    val group = InetAddress.getByName("239.255.255.220");
    test(msgs0, port, group, udp.listenMulticast(group, port)(_))
  }
  property("server") = forAll { (msgs0: List[String], port: Int) =>
    val group = InetAddress.getByName("127.0.0.1")
    test(msgs0, port, group, udp.listen(port)(_))
  }
}
