package fix

import cats.effect.{Concurrent, IO}
import fs2._
import scala.concurrent.ExecutionContext.Implicits.global

trait Usability {
  implicit def C: Concurrent[IO]
  def s: Stream[IO, String]

  val observe1 = s.evalTap(_ => IO.unit)
  val join = Stream.emit(s).parJoin(1)
  val joinUnbounded = Stream.emit(s).parJoinUnbounded
}
