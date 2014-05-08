package scalaz.stream

import java.io.{ByteArrayOutputStream, PrintStream}

import org.scalacheck.Prop.secure
import org.scalacheck.Properties

import scalaz.concurrent.Task

object PrintSpec extends Properties("io.print") {
  property("print terminates on process close") = secure {
    terminates { out =>
      Process
        .constant("word").repeat
        .to(io.print(out)).run.run
    }
  }

  property("printLines terminates on process close") = secure {
    terminates { out =>
      Process
        .constant("word").repeat
        .to(io.printLines(out)).run.run
    }
  }

  def terminates(run: PrintStream => Unit): Boolean = {
    val baos = new ByteArrayOutputStream
    val out = new PrintStream(baos)

    /* Close the output stream after it receives some input
       this should cause a well behaved process to terminate */
    Task.fork(Task.delay({
      while (baos.toByteArray.length < 10000) Thread.sleep(100)
      baos.close
      out.close
    })).timed(5000).runAsync(_ => ())

    Task.delay(run(out)).timed(1000).run
    true
  }
}
