/*
rule = v1
 */
package fix

import cats.Applicative
import cats.effect.IO
import fs2.{Stream, StreamApp}

object MyAppObject extends StreamApp[IO] {
  override def stream(args: List[String],
                      requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
    Stream
      .eval(Applicative[IO].pure("hello"))
      .flatMap(_ => Stream.eval(Applicative[IO].pure(StreamApp.ExitCode.Success))) /* assert: v1.StreamAppExitCode
                                                                        ^^^^^^^
                                                                        You can remove this
 */
}
