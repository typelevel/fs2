package fs2

import Stream.Handle
import fs2.{Pull => P, Chunk => C}
import fs2.util.{Free,Functor,Sub1}

object tee {

  type Tee[-I,-I2,+O] = (Stream[Pure,I], Stream[Pure,I2]) => Stream[Pure,O]

  def covary[F[_],I,I2,O](p: Tee[I,I2,O]): (Stream[F,I], Stream[F,I2]) => Stream[F,O] =
    p.asInstanceOf[(Stream[F,I],Stream[F,I2]) => Stream[F,O]]


  private def zipChunksWith[I,I2,O](f: (I, I2) => O)(c1: Chunk[I], c2: Chunk[I2]): Step[Chunk[O], Option[Either[Chunk[I], Chunk[I2]]]] = {
      def go(v1: Vector[I], v2: Vector[I2], acc: Vector[O]): Step[Chunk[O], Option[Either[Chunk[I], Chunk[I2]]]] = (v1, v2) match {
        case (Seq(),Seq())        => Step(Chunk.seq(acc.reverse), None)
        case (v1,   Seq())        => Step(Chunk.seq(acc.reverse), Some(Left(Chunk.seq(v1))))
        case (Seq(),   v2)        => Step(Chunk.seq(acc.reverse), Some(Right(Chunk.seq(v2))))
        case (i1 +: v1, i2 +: v2) => go(v1, v2, f(i1, i2) +: acc)
      }
      go(c1.toVector, c2.toVector, Vector.empty[O])
  }

  private type ZipWithCont[F[_],I,O,R] = Either[Step[Chunk[I], Handle[F, I]],
                                         Handle[F, I]] => Pull[F,O,R]

  private def zipWithHelper[F[_],I,I2,O]
                      (k1: ZipWithCont[F,I,O,Nothing],
                       k2: ZipWithCont[F,I2,O,Nothing])
                      (f: (I, I2) => O):
                          (Stream[F, I], Stream[F, I2]) => Stream[F, O] = {
      def zipChunksGo(s1 : Step[Chunk[I], Handle[F, I]],
                      s2 : Step[Chunk[I2], Handle[F, I2]]): Pull[F, O, Nothing] = (s1, s2) match {
                            case (c1 #: h1, c2 #: h2) => zipChunksWith(f)(c1, c2) match {
                              case (co #: r) => Pull.output(co) >> (r match {
                                case None => goB(h1, h2)
                                case Some(Left(c1rest)) => go1(c1rest, h1, h2)
                                case Some(Right(c2rest)) => go2(c2rest, h1, h2)
                              })
                            }
                       }
      def go1(c1r: Chunk[I], h1: Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        P.receiveNonemptyOption[F,I2,O,Nothing]{
          case Some(s2) => zipChunksGo(c1r #: h1, s2)
          case None => k1(Left(c1r #: h1))
        }(h2)
      }
      def go2(c2r: Chunk[I2], h1: Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        P.receiveNonemptyOption[F,I,O,Nothing]{
          case Some(s1) => zipChunksGo(s1, c2r #: h2)
          case None => k2(Left(c2r #: h2))
        }(h1)
      }
      def goB(h1 : Handle[F,I], h2: Handle[F,I2]): Pull[F, O, Nothing] = {
        P.receiveNonemptyOption[F,I,O,Nothing]{
          case Some(s1) => P.receiveNonemptyOption[F,I2,O,Nothing] {
            case Some(s2) => zipChunksGo(s1, s2)
            case None => k1(Left(s1))
          }(h2)
          case None => k2(Right(h2))
        }(h1)
      }
      _.pull2(_)(goB)
  }

  def zipAllWith[F[_],I,I2,O](pad1: I, pad2: I2)(f: (I, I2) => O): (Stream[F, I], Stream[F, I2]) => Stream[F, O] = {
      def cont1(z: Either[Step[Chunk[I], Handle[F, I]], Handle[F, I]]): Pull[F, O, Nothing] = {
        def putLeft(c: Chunk[I]) = {
          val co = Chunk.seq(c.toVector.zip( Vector.fill(c.size)(pad2)))
                        .map(f.tupled)
          P.output(co)
        }
        def contLeft(h: Handle[F,I]): Pull[F,O,Nothing] = h.receive {
            case c #: h => putLeft(c) >> contLeft(h)
        }
        z match {
          case Left(c #: h) => putLeft(c) >> contLeft(h)
          case Right(h)     => contLeft(h)
        }
      }
      def cont2(z: Either[Step[Chunk[I2], Handle[F, I2]], Handle[F, I2]]): Pull[F, O, Nothing] = {
        def putRight(c: Chunk[I2]) = {
          val co = Chunk.seq(Vector.fill(c.size)(pad1).zip(c.toVector))
                        .map(f.tupled)
          P.output(co)
        }
        def contRight(h: Handle[F,I2]): Pull[F,O,Nothing] = h.receive {
            case c #: h => putRight(c) >> contRight(h)
        }
        z match {
          case Left(c #: h) => putRight(c) >> contRight(h)
          case Right(h)     => contRight(h)
        }
      }
      zipWithHelper[F,I,I2,O](cont1, cont2)(f)
  }


  def zipWith[F[_],I,I2,O](f: (I, I2) => O) : (Stream[F, I], Stream[F, I2]) => Stream[F, O] =
    zipWithHelper[F,I,I2,O](sh => Pull.done, h => Pull.done)(f)

  def zipAll[F[_],I,I2](pad1: I, pad2: I2): (Stream[F, I], Stream[F, I2]) => Stream[F, (I, I2)] =
    zipAllWith(pad1,pad2)(Tuple2.apply)

  def zip[F[_],I,I2]: (Stream[F, I], Stream[F, I2]) => Stream[F, (I, I2)] =
    zipWith(Tuple2.apply)

  def interleaveAll[F[_], O]: (Stream[F,O], Stream[F,O]) => Stream[F,O] = { (s1, s2) =>
    (zipAll(None: Option[O], None: Option[O])(s1.map(Some.apply),s2.map(Some.apply))) flatMap {
      case (i1Opt,i2Opt) => Stream(i1Opt.toSeq :_*) ++ Stream(i2Opt.toSeq :_*)
    }
  }

  def interleave[F[_], O]: (Stream[F,O], Stream[F,O]) => Stream[F,O] =
    zip(_,_) flatMap { case (i1,i2) => Stream(i1,i2) }

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
