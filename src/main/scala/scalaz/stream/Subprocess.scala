package scalaz.stream

import java.io.{ InputStream, OutputStream }
import java.lang.{ Process => SysProcess, ProcessBuilder }
import scala.io.Codec
import scalaz.concurrent.Task

import Process._
import process1._
import scalaz.{\/-, -\/, \/}
import scalaz.\/._

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

  //def outputEx = Exchange(output, input)
  //def errorEx = Exchange(error, input)

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
      mkSink(p.getOutputStream),
      mkSource(p.getInputStream),
      mkSource(p.getErrorStream))



  def createX1(args: String*): Process[Task, SystemExchange[Bytes, Bytes]] =
    io.resource(
      Task.delay(new ProcessBuilder(args: _*).start))(
      p => Task.delay(close(p)))(
      p => Task.delay(mkSystemExchange(p))).once

  def createX2(args: String*)(implicit codec: Codec): Process[Task, Exchange[String, String]] =
    createX1(args: _*).map(_.pipeW(linesOut).mapO {
        case -\/(a) => a
        case \/-(a) => a
    }).map(_.pipeO(linesIn))


  type SystemExchange[R, W] = Exchange[R \/ R, W]

  private def mkSystemExchange(p: SysProcess): SystemExchange[Bytes, Bytes] = {
    Exchange(
      mkMergedSources(p.getErrorStream, p.getInputStream),
      mkSink(p.getOutputStream))
  }

  private def mkSink(os: OutputStream): Sink[Task, Bytes] =
    io.channel {
      (bytes: Bytes) => Task.delay {
        os.write(bytes.toArray)
        os.flush
      }
    }

  private def mkMergedSources(err: InputStream, out: InputStream): Process[Task, Bytes \/ Bytes] = {
    val errProc: Process[Task, Bytes \/ Bytes] = mkSource(err).map(left)
    val outProc: Process[Task, Bytes \/ Bytes] = mkSource(out).map(right)
    errProc.merge(outProc)
  }

  private def mkSource(is: InputStream): Process[Task, Bytes] = {
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

  // These processes are independent of Subprocess and could be moved into process1:

  /** Converts `String` to `Bytes` using the implicitly supplied `Codec`. */
  def encode(implicit codec: Codec): Process1[String, Bytes] =
    lift(s => Bytes.unsafe(s.getBytes(codec.charSet)))

  /**
   * Appends a linefeed to all input strings and converts them to `Bytes`
   * using the implicitly supplied `Codec`.
   */
  def linesOut(implicit codec: Codec): Process1[String, Bytes] =
    lift((_: String) + "\n") |> encode

  /**
   * Assembles the inputs to complete lines and converts them to `String`
   * using the implicitly supplied `Codec`. This process takes care of
   * lines that are spread over multiple inputs.
   */
  def linesIn(implicit codec: Codec): Process1[Bytes, String] = {
    /**
     * Splits the inputs into chunks separated by linefeeds. Chunks that end
     * with a linefeed are emitted as `Left` and those that don't as `Right`.
     */
    def linesOrRest: Process1[Bytes, Either[Bytes, Bytes]] = {
      def go(bytes: Bytes): Process1[Bytes, Either[Bytes, Bytes]] = {
        val (line, rest) = bytes.span(_ != '\n'.toByte)

        if (bytes.isEmpty)
          emit(Left(Bytes.empty))
        else if (rest.isEmpty)
          emit(Right(line))
        else
          emit(Left(line)) fby go(rest.drop(1))
      }
      id[Bytes].flatMap(go)
    }

    linesOrRest.chunkBy2((fst, _) => fst.isRight).map {
      _.foldLeft(Bytes.empty)(_ ++ _.merge).decode(codec.charSet)
    }
  }
}
