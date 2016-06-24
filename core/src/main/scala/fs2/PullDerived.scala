package fs2

trait PullDerived { self: fs2.Pull.type =>

  /**
   * Acquire a resource within a `Pull`. The cleanup action will be run at the end
   * of the `.close` scope which executes the returned `Pull`. The acquired
   * resource is returned as the result value of the pull.
   */
  def acquire[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,R] =
    acquireCancellable(r)(cleanup).map(_._2)

  /**
   * Like [[acquire]] but the result value is a tuple consisting of a cancellation
   * pull and the acquired resource. Running the cancellation pull frees the resource.
   * This allows the acquired resource to be released earlier than at the end of the
   * containing pull scope.
   */
  def acquireCancellable[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,(Pull[F,Nothing,Unit],R)] =
    Stream.bracketWithToken(r)(Stream.emit, cleanup).open.flatMap { h => h.await1.flatMap {
      case (token, r) #: _ => Pull.pure((Pull.release(List(token)), r))
    }}

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

  def suspend[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] = Pull.pure(()) flatMap { _ => p }

  implicit def covaryPure[F[_],W,R](p: Pull[Pure,W,R]): Pull[F,W,R] = p.covary[F]
}
