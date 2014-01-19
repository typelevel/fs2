package scalaz.stream

import org.scalacheck._
import Prop._
import scala.concurrent.duration._

object SubprocessSpec extends Properties("Subprocess") {
  property("read-only") = secure {
    val p = Subprocess.popen3("echo", "Hello World").flatMap {
      Process.sleep(1.millis) ++
      _.output
    }
    p.runLog.run.toList == List("Hello World")
  }

  property("read-only-2") = secure {
    val p = Subprocess.popen3("echo", "Hello\nWorld").flatMap {
      Process.sleep(1.millis) ++
      _.output
    }
    p.runLog.run.toList == List("Hello", "World")
  }

  property("write-only") = secure {
    val quit = Process("quit\n").toSource
    val p = Subprocess.popen3("bc").flatMap(quit to _.input)

    p.runLog.run.toList == List(())
  }

  property("read-write") = secure {
    val calc = Process("2 + 3\n").toSource
    val quit = Process("quit\n").toSource

    val p = Subprocess.popen3("bc").flatMap { s =>
      calc.to(s.input).drain  ++
      Process.sleep(1.millis) ++
      s.output                ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5")
  }

  property("read-write-2") = secure {
    val calc = Process("2 + 3\n", "3 + 5\n").toSource
    val quit = Process("quit\n").toSource

    val p = Subprocess.popen3("bc").flatMap { s =>
      calc.to(s.input).drain  ++
      Process.sleep(1.millis) ++
      s.output                ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5", "8")
  }

  property("read-write-3") = secure {
    val calc1 = Process("2 + 3\n").toSource
    val calc2 = Process("3 + 5\n").toSource
    val quit = Process("quit\n").toSource

    val p = Subprocess.popen3("bc").flatMap { s =>
      calc1.to(s.input).drain ++
      Process.sleep(1.millis) ++
      s.output                ++
      calc2.to(s.input).drain ++
      Process.sleep(1.millis) ++
      s.output                ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5", "8")
  }
}
