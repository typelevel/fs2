package fix

import cats.effect.IO
import fs2.Stream
import cats.effect.{ ExitCode, IOApp }
import cats.syntax.functor._

object Fs2v010Tov1 {
  object MyApp extends IOApp {
    override def run(args: List[String]): IO[ExitCode] = Stream.eval(IO.pure("hello")).flatMap(_ => Stream.eval(IO.pure(StreamApp.ExitCode.Success))).compile.drain.as(ExitCode.Success)
  }
}