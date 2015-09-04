package fs2

trait PullDerived { self: fs2.Pull.type =>

  def map[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => R): Pull[F,W,R] =
    flatMap(p)(f andThen pure)

  /** Write a single `W` to the output of this `Pull`. */
  def write1[F[_],W](w: W): Pull[F,W,Unit] = writes(Stream.emit(w))

  /** Write a `Chunk[W]` to the output of this `Pull`. */
  def write[F[_],W](w: Chunk[W]): Pull[F,W,Unit] = writes(Stream.chunk(w))

  /**
   * Repeatedly use the output of the `Pull` as input for the next step of the pull.
   * Halts when a step terminates with `Pull.done` or `Pull.fail`.
   */
  def loop[F[_],W,R](using: R => Pull[F,W,R]): R => Pull[F,W,Nothing] = {
    lazy val tl = loop(using)
    r => using(r) flatMap tl
  }

}
