package example

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import fs2.Stream
import fs2.Pull
import scala.concurrent.duration._

object Hello extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Stream
      .awakeEvery[IO](100.nanos)
      .map(identity _)
      .foreach(_ => IO.unit)
      .compile
      .drain
      .as(ExitCode.Success)

}
