/*
rule = v1
*/
package fix

import fs2._
import fs2.{Pipe, Sink, Stream}
import fs2.{Sink => X, Stream}

object Fs2sinkremoval {

  private def foo[F[_], A](s: Stream[F, A], sink: Sink[F, A]): Stream[F, Unit] =
    s.to(sink)

  def bar[F[_], A, B](): Sink[F, A] = ???

  def baz[F[_], A, B](): X[F, A] = ???
}
