/*
rule = v1
 */
package fix

import cats.effect.IO
import fs2._
import scala.concurrent.ExecutionContext.Implicits.global

trait Usability {
  def s: Stream[IO, String]

  val observe1 = s.observe1(_ => IO.unit)
  val join = Stream.emit(s).join(1)
  val joinUnbounded = Stream.emit(s).joinUnbounded
}
