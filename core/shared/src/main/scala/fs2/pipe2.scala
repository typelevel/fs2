package fs2

import fs2.{Pull => P}
import fs2.util.{Async,Free,Functor,Sub1}

/** Generic implementations of common 2-argument pipes. */
object pipe2 {

  // NB: Pure instances

  /** Converts a pure `Pipe2` to an effectful `Pipe2` of the specified type. */
  def covary[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] =
    p.asInstanceOf[Pipe2[F,I,I2,O]]

  private def zipChunksWith[I,I2,O](f: (I, I2) => O)(c1: Chunk[I], c2: Chunk[I2]): (Chunk[O], Option[Either[Chunk[I], Chunk[I2]]]) = {
      @annotation.tailrec
      def go(v1: Vector[I], v2: Vector[I2], acc: Vector[O]): (Chunk[O], Option[Either[Chunk[I], Chunk[I2]]]) = (v1, v2) match {
        case (Seq(),Seq())        => (Chunk.seq(acc.reverse), None)
        case (v1,   Seq())        => (Chunk.seq(acc.reverse), Some(Left(Chunk.seq(v1))))
        case (Seq(),   v2)        => (Chunk.seq(acc.reverse), Some(Right(Chunk.seq(v2))))
        case (i1 +: v1, i2 +: v2) => go(v1, v2, f(i1, i2) +: acc)
      }
      go(c1.toVector, c2.toVector, Vector.empty[O])
  }

  private type ZipWithCont[F[_],I,O,R] = Either[(Chunk[I], Handle[F, I]), Handle[F, I]] => Pull[F,O,R]

  private def zipWithHelper[F[_],I,I2,O]
                      (k1: ZipWithCont[F,I,O,Nothing],
                       k2: ZipWithCont[F,I2,O,Nothing])
                      (f: (I, I2) => O):
                          (Stream[F, I], Stream[F, I2]) => Stream[F, O] = {
      def zipChunksGo(s1 : (Chunk[I], Handle[F, I]),
                      s2 : (Chunk[I2], Handle[F, I2])): Pull[F, O, Nothing] = (s1, s2) match {
                            case ((c1, h1), (c2, h2)) => zipChunksWith(f)(c1, c2) match {
                              case ((co, r)) => Pull.output(co) >> (r match {
                                case None => goB(h1, h2)
                                case Some(Left(c1rest)) => go1(c1rest, h1, h2)
                                case Some(Right(c2rest)) => go2(c2rest, h1, h2)
                              })
                            }
                       }
      def go1(c1r: Chunk[I], h1: Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        h2.receiveOption {
          case Some(s2) => zipChunksGo((c1r, h1), s2)
          case None => k1(Left((c1r, h1)))
        }
      }
      def go2(c2r: Chunk[I2], h1: Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        h1.receiveOption {
          case Some(s1) => zipChunksGo(s1, (c2r, h2))
          case None => k2(Left((c2r, h2)))
        }
      }
      def goB(h1: Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        h1.receiveOption {
          case Some(s1) => h2.receiveOption {
            case Some(s2) => zipChunksGo(s1, s2)
            case None => k1(Left(s1))
          }
          case None => k2(Right(h2))
        }
      }
      _.pull2(_)(goB)
  }

  /**
   * Determinsitically zips elements with the specified function, terminating
   * when the ends of both branches are reached naturally, padding the left
   * branch with `pad1` and padding the right branch with `pad2` as necessary.
   */
  def zipAllWith[F[_],I,I2,O](pad1: I, pad2: I2)(f: (I, I2) => O): Pipe2[F,I,I2,O] = {
      def cont1(z: Either[(Chunk[I], Handle[F, I]), Handle[F, I]]): Pull[F, O, Nothing] = {
        def putLeft(c: Chunk[I]) = {
          val co = Chunk.seq(c.toVector.zip( Vector.fill(c.size)(pad2)))
                        .map(f.tupled)
          P.output(co)
        }
        def contLeft(h: Handle[F,I]): Pull[F,O,Nothing] = h.receive {
            case (c, h) => putLeft(c) >> contLeft(h)
        }
        z match {
          case Left((c, h)) => putLeft(c) >> contLeft(h)
          case Right(h)     => contLeft(h)
        }
      }
      def cont2(z: Either[(Chunk[I2], Handle[F, I2]), Handle[F, I2]]): Pull[F, O, Nothing] = {
        def putRight(c: Chunk[I2]) = {
          val co = Chunk.seq(Vector.fill(c.size)(pad1).zip(c.toVector))
                        .map(f.tupled)
          P.output(co)
        }
        def contRight(h: Handle[F,I2]): Pull[F,O,Nothing] = h.receive {
            case (c, h) => putRight(c) >> contRight(h)
        }
        z match {
          case Left((c, h)) => putRight(c) >> contRight(h)
          case Right(h)     => contRight(h)
        }
      }
      zipWithHelper[F,I,I2,O](cont1, cont2)(f)
  }

  /**
   * Determinsitically zips elements using the specified function,
   * terminating when the end of either branch is reached naturally.
   */
  def zipWith[F[_],I,I2,O](f: (I, I2) => O) : Pipe2[F,I,I2,O] =
    zipWithHelper[F,I,I2,O](sh => Pull.done, h => Pull.done)(f)

  /**
   * Determinsitically zips elements, terminating when the ends of both branches
   * are reached naturally, padding the left branch with `pad1` and padding the right branch
   * with `pad2` as necessary.
   */
  def zipAll[F[_],I,I2](pad1: I, pad2: I2): Pipe2[F,I,I2,(I,I2)] =
    zipAllWith(pad1,pad2)(Tuple2.apply)

  /** Determinsitically zips elements, terminating when the end of either branch is reached naturally. */
  def zip[F[_],I,I2]: Pipe2[F,I,I2,(I,I2)] =
    zipWith(Tuple2.apply)

  /** Determinsitically interleaves elements, starting on the left, terminating when the ends of both branches are reached naturally. */
  def interleaveAll[F[_], O]: Pipe2[F,O,O,O] = { (s1, s2) =>
    (zipAll(None: Option[O], None: Option[O])(s1.map(Some.apply),s2.map(Some.apply))) flatMap {
      case (i1Opt,i2Opt) => Stream(i1Opt.toSeq :_*) ++ Stream(i2Opt.toSeq :_*)
    }
  }

  /** Determinsitically interleaves elements, starting on the left, terminating when the end of either branch is reached naturally. */
  def interleave[F[_], O]: Pipe2[F,O,O,O] =
    zip(_,_) flatMap { case (i1,i2) => Stream(i1,i2) }

  /** Creates a [[Stepper]], which allows incrementally stepping a pure `Pipe2`. */
  def stepper[I,I2,O](p: Pipe2[Pure,I,I2,O]): Stepper[I,I2,O] = {
    type Read[+R] = Either[Option[Chunk[I]] => R, Option[Chunk[I2]] => R]
    def readFunctor: Functor[Read] = new Functor[Read] {
      def map[A,B](fa: Read[A])(g: A => B): Read[B] = fa match {
        case Left(f) => Left(f andThen g)
        case Right(f) => Right(f andThen g)
      }
    }
    def promptsL: Stream[Read,I] =
      Stream.eval[Read, Option[Chunk[I]]](Left(identity)).flatMap {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append(promptsL)
      }
    def promptsR: Stream[Read,I2] =
      Stream.eval[Read, Option[Chunk[I2]]](Right(identity)).flatMap {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append(promptsR)
      }

    def outputs: Stream[Read,O] = covary[Read,I,I2,O](p)(promptsL, promptsR)
    def stepf(s: Handle[Read,O]): Free[Read, Option[(Chunk[O],Handle[Read, O])]]
    = s.buffer match {
        case hd :: tl => Free.pure(Some((hd, new Handle[Read,O](tl, s.stream))))
        case List() => s.stream.step.flatMap { s => Pull.output1(s) }
         .close.runFoldFree(None: Option[(Chunk[O],Handle[Read, O])])(
          (_,s) => Some(s))
      }
    def go(s: Free[Read, Option[(Chunk[O],Handle[Read, O])]]): Stepper[I,I2,O] =
      Stepper.Suspend { () =>
        s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
          case Free.Unroll.Fail(err) => Stepper.Fail(err)
          case Free.Unroll.Pure(None) => Stepper.Done
          case Free.Unroll.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
          case Free.Unroll.Eval(recv) => recv match {
            case Left(recv) => Stepper.AwaitL(chunk => go(recv(chunk)))
            case Right(recv) => Stepper.AwaitR(chunk => go(recv(chunk)))
          }
        }
      }
    go(stepf(new Handle[Read,O](List(), outputs)))
  }

  /**
   * Allows stepping of a pure pipe. Each invocation of [[step]] results in
   * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
   * failed with an exception, it emitted a chunk of output, or it is awaiting input
   * from either the left or right branch.
   */
  sealed trait Stepper[-I,-I2,+O] {
    import Stepper._
    @annotation.tailrec
    final def step: Step[I,I2,O] = this match {
      case Suspend(s) => s().step
      case _ => this.asInstanceOf[Step[I,I2,O]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[I,I2,O](force: () => Stepper[I,I2,O]) extends Stepper[I,I2,O]

    /** Algebra describing the result of stepping a pure `Pipe2`. */
    sealed trait Step[-I,-I2,+O] extends Stepper[I,I2,O]
    /** Pipe indicated it is done. */
    final case object Done extends Step[Any,Any,Nothing]
    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any,Any,Nothing]
    /** Pipe emitted a chunk of elements. */
    final case class Emits[I,I2,O](chunk: Chunk[O], next: Stepper[I,I2,O]) extends Step[I,I2,O]
    /** Pipe is awaiting input from the left. */
    final case class AwaitL[I,I2,O](receive: Option[Chunk[I]] => Stepper[I,I2,O]) extends Step[I,I2,O]
    /** Pipe is awaiting input from the right. */
    final case class AwaitR[I,I2,O](receive: Option[Chunk[I2]] => Stepper[I,I2,O]) extends Step[I,I2,O]
  }

  // NB: Effectful instances

  /**
   * Defined as `s1.drain merge s2`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s1`.
   */
  def mergeDrainL[F[_]:Async,I,I2]: Pipe2[F,I,I2,I2] = (s1, s2) =>
    s1.drain merge s2

  /**
   * Defined as `s1 merge s2.drain`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s2`.
   */
  def mergeDrainR[F[_]:Async,I,I2]: Pipe2[F,I,I2,I] = (s1, s2) =>
    s1 merge s2.drain

  /** Like `[[merge]]`, but tags each output with the branch it came from. */
  def either[F[_]:Async,I,I2]: Pipe2[F,I,I2,Either[I,I2]] = (s1, s2) =>
    s1.map(Left(_)) merge s2.map(Right(_))

  /**
   * Let through the `s2` branch as long as the `s1` branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as either branch halts.
   */
  def interrupt[F[_]:Async,I]: Pipe2[F,Boolean,I,I] = (s1, s2) =>
    either.apply(s1.noneTerminate, s2.noneTerminate)
      .takeWhile(_.fold(halt => halt.map(!_).getOrElse(false), o => o.isDefined))
      .collect { case Right(Some(i)) => i }

  /**
   * Interleaves the two inputs nondeterministically. The output stream
   * halts after BOTH `s1` and `s2` terminate normally, or in the event
   * of an uncaught failure on either `s1` or `s2`. Has the property that
   * `merge(Stream.empty, s) == s` and `merge(fail(e), s)` will
   * eventually terminate with `fail(e)`, possibly after emitting some
   * elements of `s` first.
   */
  def merge[F[_]:Async,O]: Pipe2[F,O,O,O] = (s1, s2) => {
    def go(l: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[O], Handle[F,O])]],
           r: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[O], Handle[F,O])]]): Pull[F,O,Nothing] =
      (l race r).pull flatMap {
        case Left(l) => l.optional flatMap {
          case None => r.pull.flatMap(identity).flatMap { case (hd, tl) => P.output(hd) >> tl.echo }
          case Some((hd, l)) => P.output(hd) >> l.awaitAsync.flatMap(go(_, r))
        }
        case Right(r) => r.optional flatMap {
          case None => l.pull.flatMap(identity).flatMap { case (hd, tl) => P.output(hd) >> tl.echo }
          case Some((hd, r)) => P.output(hd) >> r.awaitAsync.flatMap(go(l, _))
        }
      }
    s1.pull2(s2) {
      (s1,s2) => s1.awaitAsync.flatMap { l => s2.awaitAsync.flatMap { r => go(l,r) }}
    }
  }

  /** Like `merge`, but halts as soon as _either_ branch halts. */
  def mergeHaltBoth[F[_]:Async,O]: Pipe2[F,O,O,O] = (s1, s2) =>
    s1.noneTerminate merge s2.noneTerminate through pipe.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s1` branch halts. */
  def mergeHaltL[F[_]:Async,O]: Pipe2[F,O,O,O] = (s1, s2) =>
    s1.noneTerminate merge s2.map(Some(_)) through pipe.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s2` branch halts. */
  def mergeHaltR[F[_]:Async,O]: Pipe2[F,O,O,O] = (s1, s2) =>
    mergeHaltL.apply(s2, s1)

  /** Like `interrupt` but resumes the stream when left branch goes to true. */
  def pause[F[_]:Async,I]: Pipe2[F,Boolean,I,I] = {
    def unpaused(
      controlFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[Boolean], Handle[F, Boolean])]],
      srcFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[I], Handle[F, I])]]
    ): Pull[F, I, Nothing] = {
      (controlFuture race srcFuture).pull.flatMap {
        case Left(controlPull) => controlPull.flatMap {
          case (c, controlHandle) =>
            if (c.last) paused(controlHandle, srcFuture)
            else controlHandle.awaitAsync.flatMap(unpaused(_, srcFuture))
        }
        case Right(srcPull) => srcPull.flatMap { case (c, srcHandle) =>
          Pull.output(c) >> srcHandle.awaitAsync.flatMap(unpaused(controlFuture, _))
        }
      }
    }

    def paused(
      controlHandle: Handle[F, Boolean],
      srcFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[I], Handle[F, I])]]
    ): Pull[F, I, Nothing] = {
      controlHandle.receive { (c, controlHandle) =>
        if (c.last) paused(controlHandle, srcFuture)
        else controlHandle.awaitAsync.flatMap { controlFuture => unpaused(controlFuture, srcFuture) }
      }
    }

    (control, src) => control.open.flatMap { controlHandle => src.open.flatMap { srcHandle =>
      controlHandle.awaitAsync.flatMap { controlFuture =>
        srcHandle.awaitAsync.flatMap { srcFuture =>
          unpaused(controlFuture, srcFuture)
        }
      }
    }}.close
  }
}
