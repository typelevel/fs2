package fs2

private[fs2] trait PipeDerived {

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit class PurePipeOps[I,O](self: Pipe[Pure,I,O]) {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe[F,I,O] =
      pipe.covary[F,I,O](self)
  }

  /** Provides operations on effectful pipes for syntactic convenience. */
  implicit class PipeOps[F[_],I,O](self: Pipe[F,I,O]) {

    /** Transforms the left input of the given `Pipe2` using a `Pipe`. */
    def attachL[I1,O2](p: Pipe2[F,O,I1,O2]): Pipe2[F,I,I1,O2] =
      (l, r) => p(self(l), r)

    /** Transforms the right input of the given `Pipe2` using a `Pipe`. */
    def attachR[I0,O2](p: Pipe2[F,I0,O,O2]): Pipe2[F,I0,I,O2] =
      (l, r) => p(l, self(r))
  }

  implicit def autoCovaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] =
    pipe.covary[F,I,O](p)

  implicit def autoCovaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] =
    pipe2.covary[F,I,I2,O](p)
}
