package object fs2 {
  type Pipe[F[_],-I,+O] = Stream[F,I] => Stream[F,O]
  type Pipe2[F[_],-I,-I2,+O] = (Stream[F,I], Stream[F,I2]) => Stream[F,O]
  type Sink[F[_],-I] = Pipe[F,I,Unit]

  implicit def autoCovaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] =
    pipe.covary[F,I,O](p)

  implicit def autoCovaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] =
    pipe2.covary[F,I,I2,O](p)
}
