package fs2

import fs2.util.{RealSupertype,Sub1,Task}

/** Various derived operations that are mixed into the `Stream` companion object. */
private[fs2]
trait StreamDerived extends PipeDerived { self: fs2.Stream.type =>

  // nb: methods are in alphabetical order

  def apply[F[_],W](a: W*): Stream[F,W] = self.chunk(Chunk.seq(a))

  def pure[W](a: W*): Stream[Pure,W] = apply[Pure,W](a: _*)

  def await1[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[A, Handle[F,A]]] =
    h.await flatMap { case Step(hd, tl) => hd.uncons match {
      case None => await1(tl)
      case Some((h,hs)) => Pull.pure(Step(h, tl push hs))
    }}

  def await1Async[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep1[F,A]] =
    h.awaitAsync map { _ map { _.map {
      case Step(hd, tl) => hd.uncons match {
        case None => Step(None, tl)
        case Some((h,hs)) => Step(Some(h), tl.push(hs))
      }}}
    }

  /**
   * The infinite `Process`, always emits `a`.
   * If for performance reasons it is good to emit `a` in chunks,
   * specify size of chunk by `chunkSize` parameter
   */
  def constant[F[_],W](w: W, chunkSize: Int = 1): Stream[F, W] =
    emits(List.fill(chunkSize)(w)) ++ constant(w, chunkSize)

  def drain[F[_],A](p: Stream[F,A]): Stream[F,Nothing] =
    p flatMap { _ => empty }

  def emit[F[_],A](a: A): Stream[F,A] = chunk(Chunk.singleton(a))

  @deprecated("use Stream.emits", "0.9")
  def emitAll[F[_],A](as: Seq[A]): Stream[F,A] = chunk(Chunk.seq(as))

  def emits[F[_],W](a: Seq[W]): Stream[F,W] = chunk(Chunk.seq(a))

  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] =
    flatMap(eval(fa)) { _ => empty }

  def eval[F[_], A](fa: F[A]): Stream[F, A] = attemptEval(fa) flatMap { _ fold(fail, emit) }

  def force[F[_],A](f: F[Stream[F, A]]): Stream[F,A] =
    flatMap(eval(f))(p => p)

  /**
   * An infinite `Stream` that repeatedly applies a given function
   * to a start value. `start` is the first value emitted, followed
   * by `f(start)`, then `f(f(start))`, and so on.
   */
  def iterate[F[_],A](start: A)(f: A => A): Stream[F,A] =
    emit(start) ++ iterate(f(start))(f)

  /**
   * Like [[iterate]], but takes an effectful function for producing
   * the next state. `start` is the first value emitted.
   */
  def iterateEval[F[_],A](start: A)(f: A => F[A]): Stream[F,A] =
    emit(start) ++ eval(f(start)).flatMap(iterateEval(_)(f))

  def map[F[_],A,B](a: Stream[F,A])(f: A => B): Stream[F,B] =
    Stream.map(a)(f)

  def mask[F[_],A](a: Stream[F,A]): Stream[F,A] =
    onError(a)(_ => empty)

  def onComplete[F[_],A](p: Stream[F,A], regardless: => Stream[F,A]): Stream[F,A] =
    onError(append(p, mask(regardless))) { err => append(mask(regardless), fail(err)) }

  def peek[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] =
    h.await flatMap { case hd #: tl => Pull.pure(hd #: tl.push(hd)) }

  def peek1[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[A, Handle[F,A]]] =
    h.await1 flatMap { case hd #: tl => Pull.pure(hd #: tl.push1(hd)) }

  def pull[F[_],F2[_],A,B](s: Stream[F,A])(using: Handle[F,A] => Pull[F2,B,Any])(implicit S: Sub1[F,F2])
  : Stream[F2,B] =
    Pull.run { Sub1.substPull(open(s)) flatMap (h => Sub1.substPull(using(h))) }

  def push1[F[_],A](h: Handle[F,A])(a: A): Handle[F,A] =
    push(h)(Chunk.singleton(a))

  /**
   * Lazily produce the range `[start, stopExclusive)`. If you want to produce
   * the sequence in one chunk, instead of lazily, use
   * `emits(start until stopExclusive)`.
   */
  def range[F[_]](start: Int, stopExclusive: Int, by: Int = 1): Stream[F,Int] =
    unfold(start){i => 
      if ((by > 0 && i < stopExclusive && start < stopExclusive) || 
          (by < 0 && i > stopExclusive && start > stopExclusive)) 
        Some((i, i + by))
      else None
    }

  /**
   * Lazily produce a sequence of nonoverlapping ranges, where each range
   * contains `size` integers, assuming the upper bound is exclusive.
   * Example: `ranges(0, 1000, 10)` results in the pairs
   * `(0, 10), (10, 20), (20, 30) ... (990, 1000)`
   *
   * Note: The last emitted range may be truncated at `stopExclusive`. For
   * instance, `ranges(0,5,4)` results in `(0,4), (4,5)`.
   *
   * @throws IllegalArgumentException if `size` <= 0
   */
  def ranges[F[_]](start: Int, stopExclusive: Int, size: Int): Stream[F,(Int,Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start){
      lower =>
        if (lower < stopExclusive)
          Some((lower -> ((lower+size) min stopExclusive), lower+size))
        else
          None
    }
  }

  def repeatPull[F[_],A,B](s: Stream[F,A])(using: Handle[F,A] => Pull[F,B,Handle[F,A]])
  : Stream[F,B] =
    pull(s)(Pull.loop(using))

  def repeatEval[F[_],A](a: F[A]): Stream[F,A] = Stream.eval(a).repeat

  def repeatPull2[F[_],A,B,C](s: Stream[F,A], s2: Stream[F,B])(
    using: (Handle[F,A], Handle[F,B]) => Pull[F,C,(Handle[F,A],Handle[F,B])])
  : Stream[F,C] =
    s.open.flatMap { s => s2.open.flatMap { s2 => Pull.loop(using.tupled)((s,s2)) }}.run

  def suspend[F[_],A](s: => Stream[F,A]): Stream[F,A] =
    emit(()) flatMap { _ => s }

  def noneTerminate[F[_],A](p: Stream[F,A]): Stream[F,Option[A]] =
    p.map(Some(_)) ++ emit(None)

  /** Produce a (potentially infinite) stream from an unfold. */
  def unfold[F[_],S,A](s0: S)(f: S => Option[(A,S)]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      f(s) match {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_],S,A](s0: S)(f: S => F[Option[(A,S)]]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      eval(f(s)).flatMap {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

  implicit class HandleOps[+F[_],+A](h: Handle[F,A]) {
    def push[A2>:A](c: Chunk[A2])(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push(h: Handle[F,A2])(c)
    def push1[A2>:A](a: A2)(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push1(h: Handle[F,A2])(a)
    def #:[H](hd: H): Step[H, Handle[F,A]] = Step(hd, h)
    def await: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = self.await(h)
    def await1: Pull[F, Nothing, Step[A, Handle[F,A]]] = self.await1(h)
    def awaitNonempty: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = Pull.awaitNonempty(h)
    def echo1: Pull[F,A,Handle[F,A]] = Pull.echo1(h)
    def echoChunk: Pull[F,A,Handle[F,A]] = Pull.echoChunk(h)
    def peek: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = self.peek(h)
    def peek1: Pull[F, Nothing, Step[A, Handle[F,A]]] = self.peek1(h)
    def awaitAsync[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep[F2,A2]] = self.awaitAsync(Sub1.substHandle(h))
    def await1Async[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep1[F2,A2]] = self.await1Async(Sub1.substHandle(h))
    def covary[F2[_]](implicit S: Sub1[F,F2]): Handle[F2,A] = Sub1.substHandle(h)
  }

  implicit class HandleInvariantEffectOps[F[_],+A](h: Handle[F,A]) {
    def invAwait1Async[A2>:A](implicit F: Async[F], A2: RealSupertype[A,A2]):
      Pull[F, Nothing, AsyncStep1[F,A2]] = self.await1Async(h)
    def invAwaitAsync[A2>:A](implicit F: Async[F], A2: RealSupertype[A,A2]):
      Pull[F, Nothing, AsyncStep[F,A2]] = self.awaitAsync(h)
    def receive1[O,B](f: Step[A,Handle[F,A]] => Pull[F,O,B]): Pull[F,O,B] = h.await1.flatMap(f)
    def receive[O,B](f: Step[Chunk[A],Handle[F,A]] => Pull[F,O,B]): Pull[F,O,B] = h.await.flatMap(f)
  }

  implicit class StreamInvariantOps[F[_],A](s: Stream[F,A]) {
    def pull[B](using: Handle[F,A] => Pull[F,B,Any]): Stream[F,B] =
      Stream.pull(s)(using)
    def pull2[B,C](s2: Stream[F,B])(using: (Handle[F,A], Handle[F,B]) => Pull[F,C,Any]): Stream[F,C] =
      s.open.flatMap { h1 => s2.open.flatMap { h2 => using(h1,h2) }}.run
    def repeatPull[B](using: Handle[F,A] => Pull[F,B,Handle[F,A]]): Stream[F,B] =
      Stream.repeatPull(s)(using)
    def repeatPull2[B,C](s2: Stream[F,B])(using: (Handle[F,A],Handle[F,B]) => Pull[F,C,(Handle[F,A],Handle[F,B])]): Stream[F,C] =
      Stream.repeatPull2(s,s2)(using)
    /** Transform this stream using the given `Pipe`. */
    def through[B](f: Pipe[F,A,B]): Stream[F,B] = f(s)
    /** Transform this stream using the given pure `Pipe`. */
    def throughp[B](f: Pipe[Pure,A,B]): Stream[F,B] = f(s)
    /** Transform this stream using the given `Pipe2`. */
    def through2[B,C](s2: Stream[F,B])(f: Pipe2[F,A,B,C]): Stream[F,C] =
      f(s,s2)
    /** Transform this stream using the given pure `Pipe2`. */
    def through2p[B,C](s2: Stream[F,B])(f: Pipe2[Pure,A,B,C]): Stream[F,C] =
      f(s,s2)
    /** Applies the given sink to this stream and drains the output. */
    def to(f: Sink[F,A]): Stream[F,Unit] = f(s).drain
  }

  implicit class StreamPureOps[+A](s: Stream[Pure,A]) {
    def toList: List[A] =
      s.covary[Task].runFold(List.empty[A])((b, a) => a :: b).run.unsafeRun.reverse
    def toVector: Vector[A] = s.covary[Task].runLog.run.unsafeRun
  }

  implicit def covaryPure[F[_],A](s: Stream[Pure,A]): Stream[F,A] = s.covary[F]
}
