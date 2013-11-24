package scalaz.stream

import org.scalacheck._
import Prop._

object ProcessesSpec extends Properties("Process1") {

  import Process._

  property("duration is near zero at first access") =  {
    val firstValueDiscrepancy = duration.take(1).runLast.run.get
    val reasonableError = 200 * 1000000 // 200 millis
    firstValueDiscrepancy.toNanos < reasonableError
  }

}
