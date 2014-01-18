package scalaz.stream

import org.scalacheck._
import Prop._

object SubprocessSpec extends Properties("Subprocess") {
  property("read-only") = secure {
    val p = Subprocess.popen("echo", "Hello World").flatMap(_.output)
    p.runLog.run.toList == List("Hello World")
  }

  property("write-only") = secure {
    val quit = Process("quit").toSource
    val p = Subprocess.popen("bc").flatMap(s => quit.to(s.input))
    p.runLog.run.toList == List(())
  }
}
