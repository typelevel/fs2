package fix

import cats.Applicative
import cats.effect.{Effect, IO}
import fs2.Stream
import cats.effect.{ ExitCode, IOApp }
import cats.syntax.functor._

class MyStreamApp[F[_]: Effect]   {
  override def program(args: List[String]
                      ): F[ExitCode] =
    Stream
      .eval(Applicative[F].pure("hello"))
      .flatMap(_ => Stream.eval(Applicative[F].pure(StreamApp.ExitCode.Success))).compile.drain.as(ExitCode.Success) 
}

object MyApp extends MyStreamApp[IO] with IOApp { def run(args: List[String]): IO[ExitCode] = program(args) }