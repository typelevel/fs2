package scalaz.stream

import java.net.InetSocketAddress
import org.scalacheck._
import Prop._
import scodec.bits.ByteVector
import java.util.concurrent.CountDownLatch
import scalaz.concurrent.Task

class UdpSpec extends Properties("udp") {

  implicit val n = Arbitrary(Gen.choose(2000,10000))

  property("server") = forAll { (msgs0: List[String], port: Int) =>
    val addr = new InetSocketAddress("127.0.0.1", port)
    val msgs = msgs0.map(s => ByteVector.view(s.getBytes("UTF8"))).toSet
    val latch = new CountDownLatch(1) // use to make sure server is listening when we start sending packets

    val client: Process[Task,Nothing] = udp.listen(port+1) {
      udp.eval_(Task.delay { latch.await }) ++
      udp.sends_(to = addr, Process.emitAll(msgs.toList))
    }

    val server: Process[Task,ByteVector] = udp.listen(port) {
      udp.eval_(Task.delay { latch.countDown }) ++
      udp.receives(1024).take(msgs0.length - 4).map(_.bytes) // some packets in theory could be dropped
    }

    // this is UDP, so no guarantees, but check to make sure we got most
    // of the messages sent by the client
    val received: Set[ByteVector] = server.merge(client).runLog.run.toSet
    val result = (received intersect msgs).size >= msgs.size - 5
    if (msgs.size <= 4) classify(true, "empty")(result)
    else if (msgs.size < 15) classify(true, "small")(result)
    else classify(true, "large")(result)
  }
}
