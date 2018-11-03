package fix

import cats.Applicative
import cats.effect.{Effect, IO}
import fs2.Stream
import cats.effect.{ ExitCode, IOApp }
import cats.syntax.functor._

object MyApp extends IOApp { override def run(args: List[String]): IO[ExitCode] = Stream.eval(Applicative[F].pure("hello")).flatMap(_ => Stream.eval(Applicative[F].pure(StreamApp.ExitCode.Success))).compile.drain.as(ExitCode.Success) }