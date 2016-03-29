/*
package fs2

import fs2.internal.Resources
import fs2.util.{Eq,Free,~>}
// import Pull3.{R,Segment,Stack,Token}
import Pull3.{Env,R,Stack,Token}

trait Pull3[F[_],O,R] { self =>
  type O0
  type R0
  def push[O2,R2](stack: Stack[F,O,O2,R,R2]): Scope[F,Stack[F,O0,O2,R0,R2]]

  def step: Scope[F,Pull3.Step[O,R,Pull3[F,O,R]]] =
    push(Stack.empty) flatMap (Pull3.step[F,O0,O,R0,R])

//  def flatMap[O2](f: O => Pull3[F,O2]): Pull3[F,O2] = new Pull3[F,O2] { type O0 = self.O0
//    def push[O3](stack: Stack[F,O2,O3]) =
//      self.push { stack pushBind f }
//  }
//  def map[O2](f: O => O2): Pull3[F,O2] = new Pull3[F,O2] { type O0 = self.O0
//    def push[O3](stack: Stack[F,O2,O3]) =
//      self.push { stack pushMap f }
//  }
//  def onError(f: Throwable => Pull3[F,O]): Pull3[F,O] = new Pull3[F,O] { type O0 = self.O0
//    def push[O2](stack: Stack[F,O,O2]) =
//      self.push { stack pushHandler f }
//  }
//  def step: Scope[F, Option[Either[Throwable,Step[Chunk[O],Pull3[F,O]]]]]
//  = push(Stack.empty[F,O]) flatMap (Pull3.step)
//
//  def uncons: Pull3[F, Option[Step[Chunk[O], Pull3[F,O]]]] =
//    Pull3.evalScope(step) flatMap {
//      case None => Pull3.emit(None)
//      case Some(Left(err)) => Pull3.fail(err)
//      case Some(Right(s)) => Pull3.emit(Some(s))
//    }
//
//  def unconsAsync(implicit F: Async[F]): Pull3[F, Async.Future[F,Option[Either[Throwable, Step[Chunk[O], Pull3[F,O]]]]]] =
//    Pull3.evalScope(Scope.tracked[F] map { tracked => step.bindTracked(tracked) }) flatMap {
//      // allocate an atomic boolean
//      // allocate a new resource which sets the boolean to false
//      // tweak the `step` to check the boolean regularly
//      // onForce, deallocate the interrupt token
//      f => Pull3.eval {
//        F.bind(F.ref[Option[Either[Throwable, Step[Chunk[O], Pull3[F,O]]]]]) { ref =>
//        F.map(F.set(ref)(f.run)) { _ => F.read(ref) }}
//      }
//    }
//
//  def translate[G[_]](f: F ~> G): Pull3[G,O] = new Pull3[G,O] {
//    type O0 = self.O0
//    def push[O2](stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]] =
//      ???
//      // self.push(stack).translate(f).map(_.translate)
//  }
}

object Pull3 {

  def fromStack[F[_],O1,O2,R1,R2](stack: Stack[F,O1,O2,R1,R2]): Pull3[F,O2,R2] = ???

  sealed trait Segment[F[_],O,R]
  object Segment {
    case class Fail[F[_],O,R](err: Throwable) extends Segment[F,O,R]
    case class Emits[F[_],O](chunks: Vector[Chunk[O]]) extends Segment[F,O,Unit]
    case class Handler[F[_],O,R](h: Throwable => Pull3[F,O,R]) extends Segment[F,O,R]
    case class Zppend[F[_],O,R](s: Pull3[F,O,R]) extends Segment[F,O,R]
    case class Done[F[_],O,R](r: R) extends Segment[F,O,R]
  }

  trait Stack[F[_],O1,O2,R1,R2] { self =>
    def eqR: Option[Eq[R1,R2]]
    def eqO: Option[Eq[O1,O2]]

    def apply[R](
      empty: (Eq[O1,O2], Eq[R1,R2]) => R,
      segment: (Segment[F,O1,R1], Stack[F,O1,O2,R1,R2]) => R,
      bindR: HR[R],
      bindO: HO[R]
    ): R

    def pushFail(err: Throwable): Stack[F,O1,O2,R1,R2] = pushSegment(Segment.Fail(err))
    def pushResult(r: R1): Stack[F,O1,O2,R1,R2] = pushSegment(Segment.Done(r))

    def pushSegment(s: Segment[F,O1,R1]): Stack[F,O1,O2,R1,R2] = new Stack[F,O1,O2,R1,R2] {
      val eqR = self.eqR; val eqO = self.eqO
      def apply[R](empty: (Eq[O1,O2], Eq[R1,R2]) => R,
                   segment: (Segment[F,O1,R1], Stack[F,O1,O2,R1,R2]) => R,
                   bindR: HR[R], bindO: HO[R]): R
        = segment(s, self)
    }
    def pushBindR[R0](f: R0 => Pull3[F,O1,R1]): Stack[F,O1,O2,R0,R2] = new Stack[F,O1,O2,R0,R2] {
      val eqR = None; val eqO = self.eqO
      def apply[R](empty: (Eq[O1,O2], Eq[R0,R2]) => R,
                   segment: (Segment[F,O1,R0], Stack[F,O1,O2,R0,R2]) => R,
                   bindR: HR[R], bindO: HO[R]): R
        = bindR.f(f, self)
    }
    def pushMapO[O0](f: O0 => O1): Stack[F,O0,O2,R1,R2] = new Stack[F,O0,O2,R1,R2] {
      val eqR = self.eqR; val eqO = None
      def apply[R](empty: (Eq[O0,O2], Eq[R1,R2]) => R,
                   segment: (Segment[F,O0,R1], Stack[F,O0,O2,R1,R2]) => R,
                   bindR: HR[R], bindO: HO[R]): R
        = bindO.f(Left(f), self)
    }
    def pushBindO[O0](f: O0 => Pull3[F,O1,R1]): Stack[F,O0,O2,R1,R2] = new Stack[F,O0,O2,R1,R2] {
      val eqR = self.eqR; val eqO = None
      def apply[R](empty: (Eq[O0,O2], Eq[R1,R2]) => R,
                   segment: (Segment[F,O0,R1], Stack[F,O0,O2,R1,R2]) => R,
                   bindR: HR[R], bindO: HO[R]): R
        = bindO.f(Right(f), self)
    }

    trait HO[+R] { def f[x]: (Either[O1 => x, O1 => Pull3[F,x,R1]], Stack[F,x,O2,R1,R2]) => R }
    trait HR[+R] { def f[x]: (R1 => Pull3[F,O1,x], Stack[F,O1,O2,x,R2]) => R }
  }

  object Stack {
    def empty[F[_],O1,R1]: Stack[F,O1,O1,R1,R1] = new Stack[F,O1,O1,R1,R1] {
      val eqR = Some(Eq.refl[R1]); val eqO = Some(Eq.refl[O1])
      def apply[R](empty: (Eq[O1,O1], Eq[R1,R1]) => R,
                   segment: (Segment[F,O1,R1], Stack[F,O1,O1,R1,R1]) => R,
                   bindR: HR[R], bindO: HO[R]): R
        = empty(Eq.refl, Eq.refl)
    }
    def segment[F[_],O1,R1](s: Segment[F,O1,R1]): Stack[F,O1,O1,R1,R1] =
      empty.pushSegment(s)
  }

  class Token()

  trait R[F[_]] { type f[x] = RF[F,x] }

  trait RF[+F[_],+A]
  object RF {
    case class Eval[F[_],A](f: F[A]) extends RF[F,A]
    case class Ask[F[_]]() extends RF[F, Env[F]]
  }
  case class Env[F[_]](resources: Resources[Token,Free[F,Unit]], interrupt: () => Boolean)

  trait Step[O,R,T]
  object Step {
    case class Fail[O,R,T](err: Throwable) extends Step[O,R,T]
    case class Done[O,R,T](r: Option[R]) extends Step[O,R,T]
    case class Cons[O,R,T](head: Chunk[O], tail: T) extends Step[O,R,T]
  }

  def step[F[_],O1,O2,R1,R2](stack: Stack[F,O1,O2,R1,R2]): Scope[F,Step[O2,R2,Pull3[F,O2,R2]]] = {
    type R = Scope[F,Step[O2,R2,Pull3[F,O2,R2]]]
    stack (
      (eqo, eqr) => Scope.pure(Step.Done(None)),
      (seg, tl) => seg match {
        case Segment.Fail(err) => tl (
          (_,_) => Scope.pure(Step.Fail(err)),
          (seg, tl) => seg match {
            // fail(err) onError h == h(err)
            case Segment.Handler(h) => h(err).push(tl).flatMap(step)
            // fail(err) ++ p2 == fail(err), etc
            case _ => step(tl.pushFail(err))
          },
          // fail(err) flatMapR f == fail(err)
          new tl.HR[R] { def f[x] = (_,tl) => step(tl.pushFail(err)) },
          // fail(err) flatMapO f == fail(err)
          new tl.HO[R] { def f[x] = (_,tl) => step(tl.pushFail(err)) }
        )
        // not syntactically possible - an `onError h` with nothing to left of it
        case Segment.Handler(_) => step(tl)
        case Segment.Zppend(p) => p.push(tl).flatMap(step)
        case Segment.Done(r) => tl.eqR match {
          case Some(eqR) => Scope.pure(Step.Done(Some(eqR(r))))
          case None => ???
        }
        case Segment.Emits(chunks) => (chunks, tl.eqO) match {
          case (Vector(chunk, chunks@_*), Some(eqO)) =>
            val tl2 = tl.pushSegment(Segment.Emits(chunks.toVector))
            Scope.pure(Step.Cons(Eq.subst[Chunk,O1,O2](chunk)(eqO), Pull3.fromStack(tl2)))
          case (Vector(), _) => step(tl.pushResult(()))
          case (_, None) => tl (
            (eqO,eqR) => ???,
            (seg,tl) => ???
          )
        }
      },
      new stack.HR[R] { def f[x] = (_,tl) => step(tl) },
      new stack.HO[R] { def f[x] = (_,tl) => step(tl) }
    )
  }
}

case class Scope[+F[_],+O](get: Free[R[F]#f,O]) {
  def map[O2](f: O => O2): Scope[F,O2] = Scope(get map f)
  def flatMap[F2[x]>:F[x],O2](f: O => Scope[F2,O2]): Scope[F2,O2] =
    Scope(get flatMap[R[F2]#f,O2] (f andThen (_.get)))
  def translate[G[_]](f: F ~> G): Scope[G,O] = ???
  def bindTracked[F2[x]>:F[x]](r: Resources[Token, Free[F2,Unit]]): Free[F2,O] =
    sys.error("todo - define in terms of translate")
}

object Scope {
  def pure[F[_],O](o: O): Scope[F,O] = Scope(Free.pure(o))
  def attemptEval[F[_],O](o: F[O]): Scope[F,Either[Throwable,O]] =
    Scope(Free.attemptEval[R[F]#f,O](Pull3.RF.Eval(o)))
  def eval[F[_],O](o: F[O]): Scope[F,O] =
    attemptEval(o) flatMap { _.fold(fail, pure) }
  def fail[F[_],O](err: Throwable): Scope[F,O] =
    Scope(Free.fail(err))
  def ask[F[_]]: Scope[F, Env[F]] =
    Scope(Free.eval[R[F]#f,Env[F]](Pull3.RF.Ask[F]()))
}
*/
