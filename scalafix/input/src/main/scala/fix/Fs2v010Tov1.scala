/*
rule = Fs2v010Tov1
*/
package fix

import cats.effect.IO
import fs2.{Stream, StreamApp}

object Fs2v010Tov1 {
  object MyApp extends StreamApp[IO] {
    override def stream(args: List[String], requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
      Stream.eval(IO.pure("hello")).flatMap(_ => Stream.eval(IO.pure(StreamApp.ExitCode.Success)))/* assert: Fs2v010Tov1.StreamAppExitCode
                                                                                        ^^^^^^^
                                                                                        You can remove this
                                                                                        */
  }
}
