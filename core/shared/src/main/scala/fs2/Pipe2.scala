package fs2

// import cats.Functor

object Pipe2 {
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
}
