package scalaz.stream

import org.scalacheck._
import Prop._
import scala.concurrent.duration._
import Process._
import Subprocess._

object SubprocessSpec extends Properties("Subprocess") {
  def sleep = Process.sleep(10.millis)

  property("read-only") = secure {
    val p = Subprocess.createX2("echo", "Hello World").flatMap {
      sleep ++
      _.read
    }
    p.runLog.run.toList == List("Hello World", "")
  }

  property("read-only-2") = secure {
    val p = Subprocess.createLineProcess("echo", "Hello\nWorld").flatMap {
      sleep ++
      _.output
    }
    p.runLog.run.toList == List("Hello", "World", "")
  }

  property("write-only") = secure {
    val quit = Process("quit").toSource
    val p = Subprocess.createLineProcess("bc").flatMap(quit to _.input)

    p.runLog.run.toList == List(())
  }

  property("read-write") = secure {
    val calc = Process("2 + 3").toSource
    val quit = Process("quit").toSource

    val p = Subprocess.createLineProcess("bc").flatMap { s =>
      calc.to(s.input).drain ++
      sleep ++
      s.output ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5", "")
  }

  property("read-write-2") = secure {
    val calc = Process("2 + 3", "3 + 5").toSource
    val quit = Process("quit").toSource

    val p = Subprocess.createLineProcess("bc").flatMap { s =>
      calc.to(s.input).drain ++
      sleep ++
      s.output ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5", "8", "")
  }

  property("read-write-3") = secure {
    val calc1 = Process("2 + 3").toSource
    val calc2 = Process("3 + 5").toSource
    val quit = Process("quit").toSource

    val p = Subprocess.createLineProcess("bc").flatMap { s =>
      calc1.to(s.input).drain ++
      calc2.to(s.input).drain ++
      sleep ++
      s.output ++
      quit.to(s.input).drain
    }
    p.runLog.run.toList == List("5", "8", "")
  }

  property("linesIn") = secure {
    val bytes = Bytes.of("Hello\nWorld".getBytes)
    val p = Process(bytes).pipe(linesIn).toSource
    p.runLog.run.toList == List("Hello", "World")
  }

  property("linesIn-2") = secure {
    val b1 = Bytes.of("Hel".getBytes)
    val b2 = Bytes.of("lo\nWorld".getBytes)

    val p = Process(b1, b2).pipe(linesIn).toSource
    p.runLog.run.toList == List("Hello", "World")
  }

  property("linesIn-3") = secure {
    val b1 = Bytes.of("Hel".getBytes)
    val b2 = Bytes.of("lo".getBytes)
    val b3 = Bytes.of("\nWorld\n".getBytes)

    val p = Process(b1, b2, b3).pipe(linesIn).toSource
    println(p.runLog.run.toList)
    p.runLog.run.toList == List("Hello", "World", "")
  }

  property("linesIn-4") = secure {
    val b1 = Bytes.of(Array[Byte](-30))
    val b2 = Bytes.of(Array[Byte](-126, -84))

    val p = Process(b1, b2).pipe(linesIn).toSource
    p.runLog.run.toList == List("€")
  }

  property("linesIn-5") = secure {
    val bytes = Bytes.of("\n".getBytes)
    val p = Process(bytes).pipe(linesIn).toSource
    p.runLog.run.toList == List("", "")
  }

  property("linesIn-6") = secure {
    val bytes = Bytes.of("Hello\n".getBytes)
    val p = Process(bytes).pipe(linesIn).toSource
    p.runLog.run.toList == List("Hello", "")
  }
}
