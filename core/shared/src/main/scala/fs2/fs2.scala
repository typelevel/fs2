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
  @deprecated("Use Pipe[F, I, Unit] instead", "1.0.2")
  type Sink[F[_], -I] = Pipe[F, I, Unit]

  /**
    * Indicates that a stream evaluates no effects.
    *
    * A `Stream[Pure,O]` can be safely converted to a `Stream[F,O]` for all `F`.
    */
  type Pure[A] <: Nothing

  /**
    * Alias for `Nothing` which works better with type inference.
    */
  type INothing <: Nothing
}
