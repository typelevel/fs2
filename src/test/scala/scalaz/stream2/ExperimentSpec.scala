package scalaz.stream2

import org.scalacheck.Properties
import org.scalacheck.Prop._
import Process._

/**
 * Created by pach on 07/03/14.
 */
object ExperimentSpec extends Properties("Experiments") {

  property("here") = secure {

    val f = (x: Int) => List.range(1, x.min(100))

    val p = Emit(-1,List(-1, -338840612, -404450079, 2147483647, -1971109425, 0, 1, -2001298503, -2147483648, 1))

    println("##########"*10 + p)
    println("P1 " + p.toList.flatMap(f).size)
    println("P2 " + p.flatMap(f andThen Process.emitAll).toList.size )

    false
  }

}
