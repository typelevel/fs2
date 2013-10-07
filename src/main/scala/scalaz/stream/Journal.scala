package scalaz.stream

import Process._
import scalaz.concurrent.Task

case class Journal[F[_],S,I](
  commit: Sink[F,S],
  commited: Process[F,S],
  reset: Sink[F,Unit],
  recover: Sink[F,Unit],
  log: Sink[F,I],
  logged: Process[F,I])

object Journal {

  /** A local, in-memory `Journal`, useful for testing. */
  def local[S,I]: Journal[Task,S,I] =
    ???
}
