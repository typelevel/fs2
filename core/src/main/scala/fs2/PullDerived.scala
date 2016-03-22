package fs2

trait PullDerived { self: fs2.Pull.type =>

  def map[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => R): Pull[F,W,R] =
    flatMap(p)(f andThen pure)

  /** Write a single `W` to the output of this `Pull`. */
  def output1[F[_],W](w: W): Pull[F,W,Unit] = outputs(Stream.emit(w))

  /** Write a `Chunk[W]` to the output of this `Pull`. */
  def output[F[_],W](w: Chunk[W]): Pull[F,W,Unit] = outputs(Stream.chunk(w))

  /**
   * Repeatedly use the output of the `Pull` as input for the next step of the pull.
   * Halts when a step terminates with `Pull.done` or `Pull.fail`.
   */
  def loop[F[_],W,R](using: R => Pull[F,W,R]): R => Pull[F,W,Nothing] =
    r => using(r) flatMap loop(using)

  implicit def covaryPure[F[_],W,R](p: Pull[Pure,W,R]): Pull[F,W,R] = p.covary[F]
}
