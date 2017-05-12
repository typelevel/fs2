package fs2

import scala.concurrent.ExecutionContext
// import cats.Functor
import cats.Id
import cats.effect.Effect

/** Generic implementations of common 2-argument pipes. */
object pipe2 {

  // NB: Pure instances

  /** Converts a pure `Pipe2` to an effectful `Pipe2` of the specified type. */
  def covary[F[_],I,I2,O](p: Pipe2[Id,I,I2,O]): Pipe2[F,I,I2,O] =
    p.asInstanceOf[Pipe2[F,I,I2,O]]

  private type ZipWithCont[F[_],I,O,R] = Either[(Segment[I,Unit], Stream[F,I]), Stream[F,I]] => Pull[F,O,Option[R]]

  private def zipWith_[F[_],I,I2,O](k1: ZipWithCont[F,I,O,Nothing], k2: ZipWithCont[F,I2,O,Nothing])(f: (I, I2) => O): Pipe2[F,I,I2,O] = {
    def go(t1: (Segment[I,Unit], Stream[F,I]), t2: (Segment[I2,Unit], Stream[F,I2])): Pull[F,O,Option[Nothing]] =
      (t1, t2) match {
        case ((hd1, tl1), (hd2, tl2)) => Pull.segment(hd1.zipWith(hd2)(f)).flatMap {
          case Left(((),extra2)) =>
            tl1.pull.receiveOption {
              case None => k2(Left((extra2, tl2)))
              case Some(tl1) => go(tl1, (extra2, tl2))
            }
          case Right(((),extra1)) =>
            tl2.pull.receiveOption {
              case None => k1(Left((extra1, tl1)))
              case Some(tl2) => go((extra1, tl1), tl2)
            }
        }
      }
    (s1, s2) => s1.pull.receiveOption {
      case Some(s1) => s2.pull.receiveOption {
        case Some(s2) => go(s1, s2)
        case None => k1(Left(s1))
      }
      case None => k2(Right(s2))
    }.close
  }

  /**
   * Determinsitically zips elements with the specified function, terminating
   * when the ends of both branches are reached naturally, padding the left
   * branch with `pad1` and padding the right branch with `pad2` as necessary.
   *
   * @example {{{
   * scala> pipe2.zipAllWith(0, 0)(_ + _)(Stream(1, 2, 3), Stream(4, 5, 6, 7)).toList
   * res0: List[Int] = List(5, 7, 9, 7)
   * }}}
   */
  def zipAllWith[F[_],I,I2,O](pad1: I, pad2: I2)(f: (I, I2) => O): Pipe2[F,I,I2,O] = {
    def cont1(z: Either[(Segment[I,Unit], Stream[F, I]), Stream[F, I]]): Pull[F,O,Option[Nothing]] = {
      def putLeft(c: Chunk[I]) = Pull.output(c.zipWith(Chunk.vector(Vector.fill(c.size)(pad2)))(f).voidResult)
      def contLeft(s: Stream[F,I]): Pull[F,O,Option[Nothing]] = s.pull.receive {
        (hd, tl) => putLeft(hd.toChunk) >> contLeft(tl)
      }
      z match {
        case Left((c, h)) => putLeft(c.toChunk) >> contLeft(h)
        case Right(h)     => contLeft(h)
      }
    }
    def cont2(z: Either[(Segment[I2,Unit], Stream[F, I2]), Stream[F, I2]]): Pull[F,O,Option[Nothing]] = {
      def putRight(c: Chunk[I2]) = Pull.output(Chunk.vector(Vector.fill(c.size)(pad1)).zipWith(c)(f).voidResult)
      def contRight(s: Stream[F,I2]): Pull[F,O,Option[Nothing]] = s.pull.receive {
        (hd, tl) => putRight(hd.toChunk) >> contRight(tl)
      }
      z match {
        case Left((c, h)) => putRight(c.toChunk) >> contRight(h)
        case Right(h)     => contRight(h)
      }
    }
    zipWith_[F,I,I2,O](cont1, cont2)(f)
  }

  /**
   * Determinsitically zips elements using the specified function,
   * terminating when the end of either branch is reached naturally.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).zipWith(Stream(4, 5, 6, 7))(_ + _).toList
   * res0: List[Int] = List(5, 7, 9)
   * }}}
   */
  def zipWith[F[_],I,I2,O](f: (I, I2) => O) : Pipe2[F,I,I2,O] =
    zipWith_[F,I,I2,O](sh => Pull.pure(None), h => Pull.pure(None))(f)

  /**
   * Determinsitically zips elements, terminating when the ends of both branches
   * are reached naturally, padding the left branch with `pad1` and padding the right branch
   * with `pad2` as necessary.
   *
   *
   * @example {{{
   * scala> pipe2.zipAll(0, 0)(Stream(1, 2, 3), Stream(4, 5, 6, 7)).toList
   * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6), (0,7))
   * }}}
   */
  def zipAll[F[_],I,I2](pad1: I, pad2: I2): Pipe2[F,I,I2,(I,I2)] =
    zipAllWith(pad1,pad2)(Tuple2.apply)

  /**
   * Determinsitically zips elements, terminating when the end of either branch is reached naturally.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).zip(Stream(4, 5, 6, 7)).toList
   * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6))
   * }}}
   */
  def zip[F[_],I,I2]: Pipe2[F,I,I2,(I,I2)] =
    zipWith(Tuple2.apply)

  /**
   * Determinsitically interleaves elements, starting on the left, terminating when the ends of both branches are reached naturally.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).interleaveAll(Stream(4, 5, 6, 7)).toList
   * res0: List[Int] = List(1, 4, 2, 5, 3, 6, 7)
   * }}}
   */
  def interleaveAll[F[_], O]: Pipe2[F,O,O,O] = { (s1, s2) =>
    (zipAll(None: Option[O], None: Option[O])(s1.map(Some.apply),s2.map(Some.apply))) flatMap {
      case (i1Opt,i2Opt) => Stream(i1Opt.toSeq :_*) ++ Stream(i2Opt.toSeq :_*)
    }
  }

  /**
   * Determinsitically interleaves elements, starting on the left, terminating when the end of either branch is reached naturally.
   *
   * @example {{{
   * scala> Stream(1, 2, 3).interleave(Stream(4, 5, 6, 7)).toList
   * res0: List[Int] = List(1, 4, 2, 5, 3, 6)
   * }}}
   */
  def interleave[F[_], O]: Pipe2[F,O,O,O] =
    zip(_,_) flatMap { case (i1,i2) => Stream(i1,i2) }

  // /** Creates a [[Stepper]], which allows incrementally stepping a pure `Pipe2`. */
  // def stepper[I,I2,O](p: Pipe2[Pure,I,I2,O]): Stepper[I,I2,O] = {
  //   type Read[+R] = Either[Option[Chunk[I]] => R, Option[Chunk[I2]] => R]
  //   def readFunctor: Functor[Read] = new Functor[Read] {
  //     def map[A,B](fa: Read[A])(g: A => B): Read[B] = fa match {
  //       case Left(f) => Left(f andThen g)
  //       case Right(f) => Right(f andThen g)
  //     }
  //   }
  //   def promptsL: Stream[Read,I] =
  //     Stream.eval[Read, Option[Chunk[I]]](Left(identity)).flatMap {
  //       case None => Stream.empty
  //       case Some(chunk) => Stream.chunk(chunk).append(promptsL)
  //     }
  //   def promptsR: Stream[Read,I2] =
  //     Stream.eval[Read, Option[Chunk[I2]]](Right(identity)).flatMap {
  //       case None => Stream.empty
  //       case Some(chunk) => Stream.chunk(chunk).append(promptsR)
  //     }
  //
  //   def outputs: Stream[Read,O] = covary[Read,I,I2,O](p)(promptsL, promptsR)
  //   def stepf(s: Handle[Read,O]): Free[Read, Option[(Chunk[O],Handle[Read, O])]]
  //   = s.buffer match {
  //       case hd :: tl => Free.pure(Some((hd, new Handle[Read,O](tl, s.stream))))
  //       case List() => s.stream.step.flatMap { s => Pull.output1(s) }
  //        .close.runFoldFree(None: Option[(Chunk[O],Handle[Read, O])])(
  //         (_,s) => Some(s))
  //     }
  //   def go(s: Free[Read, Option[(Chunk[O],Handle[Read, O])]]): Stepper[I,I2,O] =
  //     Stepper.Suspend { () =>
  //       s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
  //         case Free.Unroll.Fail(err) => Stepper.Fail(err)
  //         case Free.Unroll.Pure(None) => Stepper.Done
  //         case Free.Unroll.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
  //         case Free.Unroll.Eval(recv) => recv match {
  //           case Left(recv) => Stepper.AwaitL(chunk => go(recv(chunk)))
  //           case Right(recv) => Stepper.AwaitR(chunk => go(recv(chunk)))
  //         }
  //       }
  //     }
  //   go(stepf(new Handle[Read,O](List(), outputs)))
  // }
  //
  // /**
  //  * Allows stepping of a pure pipe. Each invocation of [[step]] results in
  //  * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
  //  * failed with an exception, it emitted a chunk of output, or it is awaiting input
  //  * from either the left or right branch.
  //  */
  // sealed trait Stepper[-I,-I2,+O] {
  //   import Stepper._
  //   @annotation.tailrec
  //   final def step: Step[I,I2,O] = this match {
  //     case Suspend(s) => s().step
  //     case _ => this.asInstanceOf[Step[I,I2,O]]
  //   }
  // }
  //
  // object Stepper {
  //   private[fs2] final case class Suspend[I,I2,O](force: () => Stepper[I,I2,O]) extends Stepper[I,I2,O]
  //
  //   /** Algebra describing the result of stepping a pure `Pipe2`. */
  //   sealed trait Step[-I,-I2,+O] extends Stepper[I,I2,O]
  //   /** Pipe indicated it is done. */
  //   final case object Done extends Step[Any,Any,Nothing]
  //   /** Pipe failed with the specified exception. */
  //   final case class Fail(err: Throwable) extends Step[Any,Any,Nothing]
  //   /** Pipe emitted a chunk of elements. */
  //   final case class Emits[I,I2,O](chunk: Chunk[O], next: Stepper[I,I2,O]) extends Step[I,I2,O]
  //   /** Pipe is awaiting input from the left. */
  //   final case class AwaitL[I,I2,O](receive: Option[Chunk[I]] => Stepper[I,I2,O]) extends Step[I,I2,O]
  //   /** Pipe is awaiting input from the right. */
  //   final case class AwaitR[I,I2,O](receive: Option[Chunk[I2]] => Stepper[I,I2,O]) extends Step[I,I2,O]
  // }

  // NB: Effectful instances

  /** Like `[[merge]]`, but tags each output with the branch it came from. */
  def either[F[_]:Effect,I,I2](implicit ec: ExecutionContext): Pipe2[F,I,I2,Either[I,I2]] = (s1, s2) =>
    s1.map(Left(_)) merge s2.map(Right(_))

  /**
   * Let through the `s2` branch as long as the `s1` branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as either branch halts.
   */
  def interrupt[F[_]:Effect,I](implicit ec: ExecutionContext): Pipe2[F,Boolean,I,I] = (s1, s2) =>
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
  def merge[F[_]:Effect,O](implicit ec: ExecutionContext): Pipe2[F,O,O,O] = (s1, s2) => {
    def go(l: AsyncPull[F,Option[(Segment[O,Unit],Stream[F,O])]],
           r: AsyncPull[F,Option[(Segment[O,Unit],Stream[F,O])]]): Pull[F,O,Unit] =
      l.race(r).pull.flatMap {
        case Left(l) =>
          l match {
            case None => r.pull.flatMap {
              case None => Pull.done
              case Some((hd, tl)) => Pull.output(hd) >> tl.pull.echo
            }
            case Some((hd, tl)) => Pull.output(hd) >> tl.pull.unconsAsync.flatMap(go(_, r))
          }
        case Right(r) =>
          r match {
            case None => l.pull.flatMap {
              case None => Pull.done
              case Some((hd, tl)) => Pull.output(hd) >> tl.pull.echo
            }
            case Some((hd, tl)) => Pull.output(hd) >> tl.pull.unconsAsync.flatMap(go(l, _))
          }
      }

    s1.pull.unconsAsync.flatMap { s1 =>
      s2.pull.unconsAsync.flatMap { s2 =>
        go(s1,s2)
      }
    }.close
  }

  /**
   * Defined as `s1.drain merge s2`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s1`.
   */
  def mergeDrainL[F[_]:Effect,I,I2](implicit ec: ExecutionContext): Pipe2[F,I,I2,I2] = (s1, s2) =>
    s1.drain merge s2

  /**
   * Defined as `s1 merge s2.drain`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s2`.
   */
  def mergeDrainR[F[_]:Effect,I,I2](implicit ec: ExecutionContext): Pipe2[F,I,I2,I] = (s1, s2) =>
    s1 merge s2.drain


  /** Like `merge`, but halts as soon as _either_ branch halts. */
  def mergeHaltBoth[F[_]:Effect,O](implicit ec: ExecutionContext): Pipe2[F,O,O,O] = (s1, s2) =>
    s1.noneTerminate merge s2.noneTerminate through pipe.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s1` branch halts. */
  def mergeHaltL[F[_]:Effect,O](implicit ec: ExecutionContext): Pipe2[F,O,O,O] = (s1, s2) =>
    s1.noneTerminate merge s2.map(Some(_)) through pipe.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s2` branch halts. */
  def mergeHaltR[F[_]:Effect,O](implicit ec: ExecutionContext): Pipe2[F,O,O,O] = (s1, s2) =>
    mergeHaltL.apply(s2, s1)

  // /** Like `interrupt` but resumes the stream when left branch goes to true. */
  // def pause[F[_]:Effect,I](implicit ec: ExecutionContext): Pipe2[F,Boolean,I,I] = {
  //   def unpaused(
  //     controlFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[Boolean], Handle[F, Boolean])]],
  //     srcFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[I], Handle[F, I])]]
  //   ): Pull[F, I, Nothing] = {
  //     (controlFuture race srcFuture).pull.flatMap {
  //       case Left(controlPull) => controlPull.flatMap {
  //         case (c, controlHandle) =>
  //           if (c.last) paused(controlHandle, srcFuture)
  //           else controlHandle.awaitAsync.flatMap(unpaused(_, srcFuture))
  //       }
  //       case Right(srcPull) => srcPull.flatMap { case (c, srcHandle) =>
  //         Pull.output(c) >> srcHandle.awaitAsync.flatMap(unpaused(controlFuture, _))
  //       }
  //     }
  //   }
  //
  //   def paused(
  //     controlHandle: Handle[F, Boolean],
  //     srcFuture: ScopedFuture[F, Pull[F, Nothing, (NonEmptyChunk[I], Handle[F, I])]]
  //   ): Pull[F, I, Nothing] = {
  //     controlHandle.receive { (c, controlHandle) =>
  //       if (c.last) paused(controlHandle, srcFuture)
  //       else controlHandle.awaitAsync.flatMap { controlFuture => unpaused(controlFuture, srcFuture) }
  //     }
  //   }
  //
  //   (control, src) => control.open.flatMap { controlHandle => src.open.flatMap { srcHandle =>
  //     controlHandle.awaitAsync.flatMap { controlFuture =>
  //       srcHandle.awaitAsync.flatMap { srcFuture =>
  //         unpaused(controlFuture, srcFuture)
  //       }
  //     }
  //   }}.close
  // }
}
