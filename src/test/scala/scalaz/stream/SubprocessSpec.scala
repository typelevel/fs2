package scalaz.stream

import org.scalacheck._
import Prop._

object SubprocessSpec extends Properties("Subprocess") {
  property("echo") = secure {
    val p = Subprocess.popen("echo", "Hello World").flatMap(_.output)
    p.runLog.run.toList == List("Hello World")
  }
}
