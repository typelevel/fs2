package fs2

import Stream.Handle
import Step.{#:}
import fs2.{Pull => P, Chunk => C}
import fs2.util.{Free,Functor,Sub1}

object tee {

  type Tee[-I,-I2,+O] = (Stream[Pure,I], Stream[Pure,I2]) => Stream[Pure,O]

  def covary[F[_],I,I2,O](p: Tee[I,I2,O]): (Stream[F,I], Stream[F,I2]) => Stream[F,O] =
    p.asInstanceOf[(Stream[F,I],Stream[F,I2]) => Stream[F,O]]

  def zipWith[F[_],I,I2,O](f: (I, I2) => O) : (Stream[F, I], Stream[F, I2]) => Stream[F, O] = {
     _.repeatPull2(_)((h1, h2) => h1 receive1 {
        case i1 #: h1 => h2 receive1 {
          case i2 #: h2 => P.output1(f(i1, i2)) >> P.pure((h1, h2))
        }
      })
  }

  def zipAllWith[F[_],I,I2,O](pad1: I, pad2: I2)(f: (I, I2) => O): (Stream[F, I], Stream[F, I2]) => Stream[F, O] = {
    def goL(h: Handle[F,I]): Pull[F, O, Nothing] =
      P.receive1((h: Step[I, Handle[F, I]]) => h match {
        case i #: h => P.output1(f(i, pad2)) >> goL(h)
      })(h)

    def goR(h: Handle[F, I2]): Pull[F, O, Nothing] =
      P.receive1((h: Step[I2, Handle[F, I2]]) => h match {
        case i #: h => P.output1(f(pad1, i)) >> goR(h)
      })(h)

    def go(h1 : Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
      P.receive1Option((h1Opt: Option[Step[I, Handle[F, I]]]) => h1Opt match {
        case Some(i1 #: h1) => P.receive1Option((h2Opt: Option[Step[I2, Handle[F, I2]]]) => h2Opt match {
          case Some(i2 #: h2) => P.output1(f(i1,i2)) >> go(h1, h2)
          case None => P.output1(f(i1, pad2)) >> goL(h1)
        })(h2)
        case None => goR(h2)
      })(h1)
    }
    _.pull2(_)(go)
  }

  def zip[F[_],I,I2]: (Stream[F, I], Stream[F, I2]) => Stream[F, (I, I2)] =
    zipWith(Tuple2.apply)

  def zipAll[F[_],I,I2](pad1: I, pad2: I2): (Stream[F, I], Stream[F, I2]) => Stream[F, (I, I2)] =
    zipAllWith(pad1,pad2)(Tuple2.apply)

  def interleave[F[_], O]: (Stream[F,O], Stream[F,O]) => Stream[F,O] =
    zip(_,_) flatMap { case (i1,i2) => Stream(i1,i2) }

  def interleaveAll[F[_], O]: (Stream[F,O], Stream[F,O]) => Stream[F,O] = { (s1, s2) =>
    (zipAll(None: Option[O], None: Option[O])
          (s1.map(Some.apply),s2.map(Some.apply))) flatMap { case (i1Opt,i2Opt) =>
            Stream(i1Opt.toSeq :_*) ++ Stream(i2Opt.toSeq :_*)
          }
        }

  def stepper[I,I2,O](p: Tee[I,I2,O]): Stepper[I,I2,O] = {
    type Read[+R] = Either[Option[Chunk[I]] => R, Option[Chunk[I2]] => R]
    def readFunctor: Functor[Read] = new Functor[Read] {
      def map[A,B](fa: Read[A])(g: A => B): Read[B] = fa match {
        case Left(f) => Left(f andThen g)
        case Right(f) => Right(f andThen g)
      }
    }
    def promptsL: Stream[Read,I] =
      Stream.eval[Read, Option[Chunk[I]]](Left(identity)).flatMap[Read,I] {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append[Read,I](promptsL)
      }
    def promptsR: Stream[Read,I2] =
      Stream.eval[Read, Option[Chunk[I2]]](Right(identity)).flatMap[Read,I2] {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append[Read,I2](promptsR)
      }

    def outputs: Stream[Read,O] = covary[Read,I,I2,O](p)(promptsL, promptsR)
    def stepf(s: Handle[Read,O]): Free[Read, Option[Step[Chunk[O],Handle[Read, O]]]]
    = s.buffer match {
        case hd :: tl => Free.pure(Some(Step(hd, new Handle[Read,O](tl, s.stream))))
        case List() => s.stream.step.flatMap { s => Pull.output1(s) }
         .run.runFold(None: Option[Step[Chunk[O],Handle[Read, O]]])(
          (_,s) => Some(s))
      }
    def go(s: Free[Read, Option[Step[Chunk[O],Handle[Read, O]]]]): Stepper[I,I2,O] =
      Stepper.Suspend { () =>
        s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
          case Free.Unroll.Fail(err) => Stepper.Fail(err)
          case Free.Unroll.Pure(None) => Stepper.Done
          case Free.Unroll.Pure(Some(s)) => Stepper.Emits(s.head, go(stepf(s.tail)))
          case Free.Unroll.Eval(recv) => recv match {
            case Left(recv) => Stepper.AwaitL(chunk => go(recv(chunk)))
            case Right(recv) => Stepper.AwaitR(chunk => go(recv(chunk)))
          }
        }
      }
    go(stepf(new Handle[Read,O](List(), outputs)))
  }

  sealed trait Stepper[-I,-I2,+O] {
    import Stepper._
    @annotation.tailrec
    final def step: Step[I,I2,O] = this match {
      case Suspend(s) => s().step
      case _ => this.asInstanceOf[Step[I,I2,O]]
    }
  }

  object Stepper {
    private[fs2] case class Suspend[I,I2,O](force: () => Stepper[I,I2,O]) extends Stepper[I,I2,O]

    sealed trait Step[-I,-I2,+O] extends Stepper[I,I2,O]
    case object Done extends Step[Any,Any,Nothing]
    case class Fail(err: Throwable) extends Step[Any,Any,Nothing]
    case class Emits[I,I2,O](chunk: Chunk[O], next: Stepper[I,I2,O]) extends Step[I,I2,O]
    case class AwaitL[I,I2,O](receive: Option[Chunk[I]] => Stepper[I,I2,O]) extends Step[I,I2,O]
    case class AwaitR[I,I2,O](receive: Option[Chunk[I2]] => Stepper[I,I2,O]) extends Step[I,I2,O]
  }
}
