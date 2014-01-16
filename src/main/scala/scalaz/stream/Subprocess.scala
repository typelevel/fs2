package scalaz.stream

import java.io.{ InputStream, OutputStream }
import java.lang.{ Process => JavaProcess, ProcessBuilder }
import scala.io.{ Codec, Source }
import scalaz.concurrent.Task
import Process._

case class Subprocess[+R, -W](
  input: Sink[Task, W],
  output: Process[Task, R],
  error: Process[Task, R])

object Subprocess {
  def popen(args: String*)(implicit codec: Codec): Process[Task, Subprocess[String, String]] = {
    io.resource(Task.delay(new ProcessBuilder(args: _*).start))(
        p => Task.now(close(p)))(p => Task.now(mkSubprocess(p))).once
  }

  private def mkSubprocess(p: JavaProcess)(implicit codec: Codec): Subprocess[String, String] = {
    Subprocess(
      writeTo(p.getOutputStream),
      readFrom(p.getInputStream),
      readFrom(p.getErrorStream))
  }

  private def writeTo(os: OutputStream)(implicit codec: Codec): Sink[Task, String] =
    io.channel {
      (s: String) => Task.delay {
        os.write(s.getBytes(codec.charSet))
        os.flush
      }
    }

  private def readFrom(is: InputStream)(implicit codec: Codec): Process[Task, String] = {
    val lines = Source.fromInputStream(is).getLines
    val readNext = Task.delay { if (lines.hasNext) lines.next else throw End }
    repeatEval(readNext)
  }

  private def close(p: JavaProcess): Int = {
    p.getOutputStream.close
    p.getInputStream.close
    p.getErrorStream.close
    p.waitFor
  }
}
