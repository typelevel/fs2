package scalaz.stream

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.ScheduledExecutorService

import org.scalacheck.Prop.secure
import org.scalacheck.Properties

import scalaz.concurrent.Task

class PrintSpec extends Properties("io.print") {
  implicit val S: ScheduledExecutorService = DefaultScheduler

  property("print terminates on process close") = secure {
    terminates { out =>
      Process
        .constant("word").toSource.repeat
        .to(io.print(out)).run.run
    }
  }

  property("printLines terminates on process close") = secure {
    terminates { out =>
      Process
        .constant("word").toSource.repeat
        .to(io.printLines(out)).run.run
    }
  }

  property("print outputs values and terminates") = secure {
    val baos = new ByteArrayOutputStream
    val out = new PrintStream(baos)

    val from = 0
    val to   = 1000
    val mid  = (to - from) / 2

    Process.range(from, to)
      // Close the output stream in the middle of emitted sequence
      .evalMap( i =>
        Task.delay {
          if (i == mid) { baos.close; out.close }
          i
        }
      )
      .map(_.toString)
      .to(io.print(out)).run.timed(5000).run

    baos.toString == List.range(from, mid).map(_.toString).foldLeft("")(_ + _)
  }

  def terminates(run: PrintStream => Unit): Boolean = {
    val baos = new ByteArrayOutputStream
    val out = new PrintStream(baos)

    /* Close the output stream after it receives some input
       this should cause a well behaved process to terminate */
    Task.fork(Task.delay({
      while (baos.toByteArray.length < 1000) Thread.sleep(50)
      baos.close
      out.close
    })).timed(5000).runAsync(_ => ())

    Task.delay(run(out)).timed(5000).run
    true
  }
}
