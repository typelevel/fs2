package fs2

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

import cats.effect._
import cats.effect.implicits._
import cats.implicits._

import fs2.async.Ref
import fs2.async.mutable.Signal
import fs2.StreamApp.ExitCode

abstract class StreamApp[F[_]](implicit F: Effect[F]) {

  /** An application stream that should never emit or emit a single ExitCode */
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F,ExitCode]

  /** Adds a shutdown hook that interrupts the stream and waits for it to finish */
  private def addShutdownHook(requestShutdown: Signal[F,Boolean], halted: Signal[IO,Boolean]): F[Unit] =
    F.delay {
      sys.addShutdownHook {
        (requestShutdown.set(true).runAsync(_ => IO.unit) *>
          halted.discrete.takeWhile(_ == false).run).unsafeRunSync()
      }
      ()
    }

  private val directEC: ExecutionContextExecutor =
    new ExecutionContextExecutor {
      def execute(runnable: Runnable): Unit =
        try runnable.run()
        catch { case t: Throwable => reportFailure(t) }

      def reportFailure(t: Throwable): Unit = ExecutionContext.defaultReporter(t)
    }

  /** Exposed for testing, so we can check exit values before the dramatic sys.exit */
  private[fs2] def doMain(args: List[String]): IO[ExitCode] = {
    implicit val ec: ExecutionContext = directEC
    async.ref[IO, ExitCode].flatMap { exitCodeRef =>
    async.signalOf[IO, Boolean](false).flatMap { halted =>
      runStream(args, exitCodeRef, halted)
    }}
  }

  /**
    * Runs the application stream to an ExitCode.
    *
    * @param args The command line arguments
    * @param exitCodeRef A ref that will be set to the exit code from the stream
    * @param halted A signal that is set when the application stream is done
    * @param ec Implicit EC to run the application stream
    * @return An IO that will produce an ExitCode
    */
  private[fs2] def runStream(args: List[String], exitCodeRef: Ref[IO,ExitCode], halted: Signal[IO,Boolean])
                            (implicit ec: ExecutionContext): IO[ExitCode] =
    async.signalOf[F, Boolean](false).flatMap { requestShutdown =>
      addShutdownHook(requestShutdown, halted) *>
      stream(args, requestShutdown.set(true)).interruptWhen(requestShutdown).take(1).runLast
    }.runAsync {
      case Left(t) =>
        IO(t.printStackTrace()) *>
          halted.set(true) *>
          exitCodeRef.setSyncPure(ExitCode.error)
      case Right(exitCode) =>
        halted.set(true) *>
          exitCodeRef.setSyncPure(exitCode.getOrElse(ExitCode.success))
    } *> exitCodeRef.get

  def main(args: Array[String]): Unit =
    sys.exit(doMain(args.toList).unsafeRunSync.code.toInt)
}

object StreamApp {
  final case class ExitCode(code: Byte)

  object ExitCode {
    def fromInt(code: Int): ExitCode = ExitCode(code.toByte)
    val success: ExitCode = ExitCode(0)
    val error: ExitCode = ExitCode(1)
  }
}
