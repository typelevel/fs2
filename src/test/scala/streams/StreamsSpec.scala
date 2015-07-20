package streams

import org.scalacheck.Prop._
import org.scalacheck._

import java.util.concurrent.atomic.AtomicInteger

class StreamsSpec extends Properties("Streams") {

  val P = NF
  import P._

  case object FailWhale extends RuntimeException("the system... is down")

  val one = emit(1) ++ emit(2)
}
