/*
rule = v1
*/
package fix

import akka.stream.scaladsl.Sink

object AkkaSink {
  val s: Sink[Int, Unit] = ???
}
