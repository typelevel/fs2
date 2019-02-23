/*
rule = v1
 */
package fix

import cats.Applicative
import cats.effect.{Effect, IO}
import fs2.{Stream, StreamApp}

class MyStreamApp[F[_]: Effect] extends StreamApp[F] {
  override def stream(args: List[String],
                      requestShutdown: F[Unit]): fs2.Stream[F, StreamApp.ExitCode] =
    Stream
      .eval(Applicative[F].pure("hello"))
      .flatMap(_ => Stream.eval(Applicative[F].pure(StreamApp.ExitCode.Success))) /* assert: v1.StreamAppExitCode
                                                                       ^^^^^^^
                                                                       You can remove this
 */
}

object MyApp extends MyStreamApp[IO]
