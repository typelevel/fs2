package scalaz.stream

import java.io.{ InputStream, OutputStream }
import java.lang.{ Process => SysProcess, ProcessBuilder }
import scala.io.Codec
import scalaz.concurrent.Task

import Process._
import process1._

/*
TODO:
- Naming: Having Process and Subprocess as types that are unrelated is
  unfortunate. Possible alternative are: Sys(tem)Exchange, ProcExchange,
  SystemProc, ChildProc, ProgramExchange?

- Expose the return value of Process.waitFor().

- Support ProcessBuilder's directory, environment, redirectErrrorStream methods.
  Possibly by adding more parameters the the create?Process methods.

- Support terminating a running Subprocess via Process.destory().

- Find better names for createRawProcess and createLineProcess.
*/

case class Subprocess[+R, -W](
  input: Sink[Task, W],
  output: Process[Task, R],
  error: Process[Task, R]) {

  def contramapW[W2](f: W2 => W): Subprocess[R, W2] =
    Subprocess(input.contramap(f), output, error)

  def mapR[R2](f: R => R2): Subprocess[R2, W] =
    Subprocess(input, output.map(f), error.map(f))

  def mapSink[W2](f: Sink[Task, W] => Sink[Task, W2]): Subprocess[R, W2] =
    Subprocess(f(input), output, error)

  def mapSources[R2](f: Process[Task, R] => Process[Task, R2]): Subprocess[R2, W] =
    Subprocess(input, f(output), f(error))
}

object Subprocess {
  def createRawProcess(args: String*): Process[Task, Subprocess[Bytes, Bytes]] =
    io.resource(
      Task.delay(new ProcessBuilder(args: _*).start))(
      p => Task.delay(close(p)))(
      p => Task.delay(mkRawSubprocess(p))).once

  def createLineProcess(args: String*)(implicit codec: Codec): Process[Task, Subprocess[String, String]] =
    createRawProcess(args: _*).map {
      _.mapSources(_.pipe(linesIn)).mapSink(_.pipeIn(linesOut))
    }

  private def mkRawSubprocess(p: SysProcess): Subprocess[Bytes, Bytes] =
    Subprocess(
      mkRawSink(p.getOutputStream),
      mkRawSource(p.getInputStream),
      mkRawSource(p.getErrorStream))

  private def mkRawSink(os: OutputStream): Sink[Task, Bytes] =
    io.channel {
      (bytes: Bytes) => Task.delay {
        os.write(bytes.toArray)
        os.flush
      }
    }

  private def mkRawSource(is: InputStream): Process[Task, Bytes] = {
    val maxSize = 4096
    val buffer = Array.ofDim[Byte](maxSize)

    val readChunk = Task.delay {
      val size = math.min(is.available, maxSize)
      if (size > 0) {
        is.read(buffer, 0, size)
        Bytes.of(buffer, 0, size)
      } else throw End
    }
    repeatEval(readChunk)
  }

  private def close(p: SysProcess): Int = {
    p.getOutputStream.close
    p.getInputStream.close
    p.getErrorStream.close
    p.waitFor
  }

  // These processes are independent of Subprocess:

  def linesOut(implicit codec: Codec): Process1[String, Bytes] =
    lift((_: String) + "\n") |> encode

  /** Convert `String` to `Bytes` using the implicitly supplied `Codec`. */
  def encode(implicit codec: Codec): Process1[String, Bytes] =
    lift(s => Bytes.unsafe(s.getBytes(codec.charSet)))

  def linesIn(implicit codec: Codec): Process1[Bytes, String] = {
    def linesOrRest: Process1[Bytes, Either[Bytes, Bytes]] =
      id[Bytes].flatMap { bytes =>
        val (line, lfAndRest) = bytes.span(_ != '\n'.toByte)
        val rest = lfAndRest.drop(1)

        if (bytes.isEmpty)
          emit(Left(bytes))
        else if (lfAndRest.isEmpty)
          emit(Right(line))
        else
          emit(Left(line)) fby (emit(rest) |> linesOrRest)
      }

    linesOrRest.chunkBy2((a, _) => a.isRight).map {
      _.foldLeft(Bytes.empty)(_ ++ _.merge).decode(codec.charSet)
    }
  }
}
