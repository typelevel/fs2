package fs2

import Stream.Handle
import Step.{#:}

object wye {

  trait Wye[-I,-I2,+O] {
    def run[F[_]:Async]: (Stream[F,I], Stream[F,I2]) => Stream[F,O]
    def apply[F[_]:Async](s: Stream[F,I], s2: Stream[F,I2]): Stream[F,O] = run.apply(s, s2)
  }

  // todo: supply an Async which is totally sequential?
}
