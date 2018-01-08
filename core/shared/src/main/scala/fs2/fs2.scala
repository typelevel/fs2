package object fs2 {

  /**
    * A stream transformation represented as a function from stream to stream.
    *
    * Pipes are typically applied with the `through` operation on `Stream`.
    */
  type Pipe[F[_], -I, +O] = Stream[F, I] => Stream[F, O]

  /**
    * A stream transformation that combines two streams in to a single stream,
    * represented as a function from two streams to a single stream.
    *
    * `Pipe2`s are typically applied with the `through2` operation on `Stream`.
    */
  type Pipe2[F[_], -I, -I2, +O] = (Stream[F, I], Stream[F, I2]) => Stream[F, O]

  /**
    * A pipe that converts a stream to a `Stream[F,Unit]`.
    *
    * Sinks are typically applied with the `to` operation on `Stream`.
    */
  type Sink[F[_], -I] = Pipe[F, I, Unit]

  // Trick to get right-biased syntax for Either in 2.11 while retaining source compatibility with 2.12 and leaving
  // -Xfatal-warnings and -Xwarn-unused-imports enabled. Delete when no longer supporting 2.11.
  private[fs2] implicit class EitherSyntax[L, R](private val self: Either[L, R]) extends AnyVal {
    def map[R2](f: R => R2): Either[L, R2] = self match {
      case Right(r)    => Right(f(r))
      case l @ Left(_) => l.asInstanceOf[Either[L, R2]]
    }
    def flatMap[R2](f: R => Either[L, R2]): Either[L, R2] = self match {
      case Right(r)    => f(r)
      case l @ Left(_) => l.asInstanceOf[Either[L, R2]]
    }
    def toOption: Option[R] = self match {
      case Right(r)    => Some(r)
      case l @ Left(_) => None
    }
  }
}
