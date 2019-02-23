package fix

import cats.Applicative
import cats.effect.IO
import fs2.Stream
import cats.effect.{ ExitCode, IOApp }
import cats.syntax.functor._

object MyAppObject extends IOApp {
  override def run(args: List[String]
                      ): IO[ExitCode] =
    Stream
      .eval(Applicative[IO].pure("hello"))
      .flatMap(_ => Stream.eval(Applicative[IO].pure(StreamApp.ExitCode.Success))).compile.drain.as(ExitCode.Success) 
}