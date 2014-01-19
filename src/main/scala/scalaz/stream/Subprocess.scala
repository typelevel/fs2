package scalaz.stream

import java.io.{ InputStream, OutputStream }
import java.lang.{ Process => JavaProcess, ProcessBuilder }
import scala.io.{ Codec, Source }
import scalaz.concurrent.Task
import scalaz.std.option._
import scalaz.std.string._
import scalaz.syntax.semigroup._
import Process._

case class Subprocess[+R, -W](
  input: Sink[Task, W],
  output: Process[Task, R],
  error: Process[Task, R])

object Subprocess {
  def popen2(args: String*): Process[Task, Subprocess[Array[Byte], Array[Byte]]] =
    io.resource {
      Task.delay(new ProcessBuilder(args: _*).start)
    } {
      p => Task.delay(close(p))
    } {
      p => Task.delay(mkSubprocess(p))
    }.once

  def popen3(args: String*)(implicit codec: Codec): Process[Task, Subprocess[String, String]] = {
    popen2(args: _*).map { sp =>
      Subprocess(
        asStringSink(sp.input),
        asLineSource(sp.output),
        asLineSource(sp.error))
    }
  }

  private def mkSubprocess(p: JavaProcess): Subprocess[Array[Byte], Array[Byte]] =
    Subprocess(
      mkSink(p.getOutputStream),
      mkSource(p.getInputStream),
      mkSource(p.getErrorStream))

  private def mkSink(os: OutputStream): Sink[Task, Array[Byte]] =
    io.channel {
      (bytes: Array[Byte]) => Task.delay {
        os.write(bytes)
        os.flush
      }
    }

  private def mkSource(is: InputStream): Process[Task, Array[Byte]] = {
    val maxSize = 4096
    val readChunk = Task.delay {
      val size = math.min(is.available, maxSize)
      if (size > 0) {
        val buffer = Array.ofDim[Byte](size)
        is.read(buffer)
        buffer
      } else throw End
    }
    repeatEval(readChunk)
  }

  private def close(p: JavaProcess): Int = {
    p.getOutputStream.close
    p.getInputStream.close
    p.getErrorStream.close
    p.waitFor
  }

  private def asStringSink(sink: Sink[Task, Array[Byte]])(implicit codec: Codec): Sink[Task, String] =
    sink.contramap(_.getBytes(codec.charSet))

  private def asLineSource(source: Process[Task, Array[Byte]])(implicit codec: Codec): Process[Task, String] = {
    def isNewline(c: Char): Boolean = c == '\r' || c == '\n'

    var carry: Option[String] = None
    source.flatMap { bytes =>
      val complete = bytes.lastOption.fold(true)(b => isNewline(b.toChar))
      val lines = Source.fromBytes(bytes).getLines.toVector

      val head = carry mappend lines.headOption
      val tail = lines.drop(1)

      val (completeLines, nextCarry) =
        if (complete)
          (head.toVector ++ tail, None)
        else if (tail.nonEmpty)
          (head.toVector ++ tail.init, tail.lastOption)
        else
          (Vector.empty, head)

      carry = nextCarry
      emitSeq(completeLines)
    }.onComplete(emitSeq(carry.toSeq))
  }
}
