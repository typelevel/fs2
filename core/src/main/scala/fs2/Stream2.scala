package fs2

import fs2.internal.Resources
import fs2.util.{Catenable,Eq,Free,Sub1,~>,RealSupertype}
import Stream2.{Env,R,Stack,Token}

trait Stream2[F[_],O] { self =>
  type O0
  def push[O2](stack: Stack[F,O,O2]): Scope[F,Stack[F,O0,O2]]

  def flatMap[O2](f: O => Stream2[F,O2]): Stream2[F,O2] = new Stream2[F,O2] { type O0 = self.O0
    def push[O3](stack: Stack[F,O2,O3]) = self.push { stack pushBind f }
  }
  def map[O2](f: O => O2): Stream2[F,O2] = new Stream2[F,O2] { type O0 = self.O0
    def push[O3](stack: Stack[F,O2,O3]) = self.push { stack pushMap f }
  }
  def onError(f: Throwable => Stream2[F,O]): Stream2[F,O] = new Stream2[F,O] { type O0 = self.O0
    def push[O2](stack: Stack[F,O,O2]) = self.push { stack pushHandler f }
  }
  def maskErrors: Stream2[F,O] = self.onError(e => Stream2.empty)
  def drain[O2]: Stream2[F,O2] = self.flatMap(_ => Stream2.empty)

  def onComplete(s2: Stream2[F,O]): Stream2[F,O] =
    Stream2.zppend(self onError (e => Stream2.zppend(s2, Stream2.fail(e))), s2)

  def step: Scope[F, Option[Either[Throwable,Step[Chunk[O],Stream2[F,O]]]]]
  = push(Stack.empty[F,O]) flatMap (Stream2.step)

  def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldScope(z)(f).bindEnv(Env(Resources.empty[Token,Free[F,Unit]], () => false))

  def runFoldScope[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] = step flatMap {
    case None => Scope.pure(z)
    case Some(Left(err)) => Scope.fail(err)
    case Some(Right(Step(hd,tl))) =>
      try tl.runFoldScope(hd.foldLeft(z)(f))(f)
      catch { case e: Throwable => Scope.fail(e) }
  }

  def uncons: Stream2[F, Option[Step[Chunk[O], Stream2[F,O]]]] =
    Stream2.evalScope(step) flatMap {
      case None => Stream2.emit(None)
      case Some(Left(err)) => Stream2.fail(err)
      case Some(Right(s)) => Stream2.emit(Some(s))
    }

  def stepAsync(implicit F: Async[F]): Stream2[F, Async.Future[F,Stream2[F,O]]] =
    unconsAsync map { f => f map {
      case None => Stream2.empty
      case Some(Left(err)) => Stream2.fail(err)
      case Some(Right(Step(hd, tl))) => Stream2.zppend(Stream2.chunk(hd), tl)
    }}

  def unconsAsync(implicit F: Async[F]): Stream2[F,Async.Future[F,Option[Either[Throwable, Step[Chunk[O], Stream2[F,O]]]]]] =
  Stream2.eval(F.ref[Option[Either[Throwable, Step[Chunk[O],Stream2[F,O]]]]]).flatMap { ref =>
    val token = new Token()
    val resources = Resources.empty[Token,Free[F,Unit]]
    val interrupt = new java.util.concurrent.atomic.AtomicBoolean(false)
    val rootCleanup = Free.suspend { resources.closeAll match {
      case None =>
        Free.eval(F.get(ref)) flatMap { _ =>
          resources.closeAll match {
            case None => Free.pure(())
            case Some(resources) => Stream2.runCleanup(resources)
          }
        }
        case Some(resources) =>
          Stream2.runCleanup(resources)
    }}
    def tweakEnv: Scope[F,Unit] =
      Scope.startAcquire(token) flatMap { _ => Scope.finishAcquire(token, rootCleanup) }
    val s: F[Unit] = F.set(ref) { step.bindEnv(Stream2.Env(resources, () => resources.isClosed)).run }
    Stream2.evalScope(tweakEnv).flatMap { _ =>
      Stream2.eval(s) map { _ =>
        // todo: add appendOnForce which deallocates the root token when forced
        F.read(ref)
      }
    }
  }

  def translate[G[_]](f: F ~> G): Stream2[G,O] = new Stream2[G,O] {
    type O0 = self.O0
    def push[O2](stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]] =
      ???
      // self.push(stack).translate(f).map(_.translate)
  }
}

import Pull3._

// todo - fill in ???s, add variance, then ship it

class Pull3[F[_],O,R](val get: Scope[F,Free[P[F,O]#f,Option[Either[Throwable,R]]]]) {

  def run: Stream2[F,O] = Stream2.scope { Stream2.evalScope { get map { f =>
    type G[x] = Stream2[F,O]; type Out = Option[Either[Throwable,R]]
    f.fold[P[F,O]#f,G,Out](
      o => o match {
        case None => Stream2.empty
        case Some(e) => e.fold(Stream2.fail, _ => Stream2.empty)
      },
      err => Stream2.fail(err),
      new Free.B[P[F,O]#f,G,Out] { def f[x] = (r, g) => r match {
        case Left(PF.Eval(fr)) => Stream2.eval(fr) flatMap g
        case Left(PF.Output(o)) => Stream2.zppend(o, Stream2.suspend(g(())))
        case Right(r) => Stream2.Try(g(r))
      }}
    )(Sub1.sub1[P[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }} flatMap (identity) }

  def flatMap[R2](f: R => Pull3[F,O,R2]): Pull3[F,O,R2] = ???
}

object Pull3 {

  trait P[F[_],O] { type f[x] = PF[F,O,x] }

  sealed trait PF[+F[_],+O,+R]
  object PF {
    case class Eval[F[_],O,R](f: F[R]) extends PF[F,O,R]
    case class Output[F[_],O](s: Stream2[F,O]) extends PF[F,O,Unit]
  }

  def output[F[_],O](s: Stream2[F,O]): Pull3[F,O,Unit] =
    new Pull3(Scope.pure(Free.eval[P[F,O]#f,Unit](PF.Output(s)).map(_ => Some(Right(())))))
}

object Stream2 {

  private[fs2]
  class Token()

  private[fs2]
  case class Env[F[_]](tracked: Resources[Token,Free[F,Unit]], interrupted: () => Boolean)

  trait R[F[_]] { type f[x] = RF[F,x] }

  sealed trait RF[+F[_],+A]
  object RF {
    case class Eval[F[_],A](f: F[A]) extends RF[F,A]
    case object Interrupted extends RF[Nothing,Boolean]
    case object Snapshot extends RF[Nothing,Set[Token]]
    case class NewSince(snapshot: Set[Token]) extends RF[Nothing,List[Token]]
    case class Release(tokens: List[Token]) extends RF[Nothing,Unit]
    case class StartAcquire(token: Token) extends RF[Nothing,Unit]
    case class FinishAcquire[F[_]](token: Token, cleanup: Free[F,Unit]) extends RF[F,Unit]
    case class CancelAcquire(token: Token) extends RF[Nothing,Unit]
  }

  case object Interrupted extends Exception { override def fillInStackTrace = this }

  private[fs2]
  def Try[F[_],O](s: => Stream2[F,O]): Stream2[F,O] =
    try s
    catch { case e: Throwable => fail(e) }

  def step[F[_],O0,O](stack: Stack[F,O0,O])
  : Scope[F,Option[Either[Throwable,Step[Chunk[O],Stream2[F,O]]]]]
  = Scope.interrupted.flatMap { interrupted =>
    if (interrupted) Scope.pure(Some(Left(Interrupted)))
    else stack (
      (segs, eq) => Eq.subst[({ type f[x] = Catenable[Segment[F,x]] })#f, O0, O](segs)(eq).uncons match {
        case None => Scope.pure(None)
        case Some((hd, segs)) => hd match {
          case Segment.Fail(err) => Stack.fail[F,O](segs)(err) match {
            case Left(err) => Scope.pure(Some(Left(err)))
            case Right((s, segs)) => step(Stack.segments(segs).pushZppend(s))
          }
          case Segment.Emit(chunk) => Scope.pure(Some(Right(Step(chunk, Stream2.segments(segs)))))
          case Segment.Handler(h) => step(Stack.segments(segs))
          case Segment.Zppend(s) => s.push(Stack.segments(segs)) flatMap (step)
        }
      },
      new stack.H[Scope[F,Option[Either[Throwable,Step[Chunk[O],Stream2[F,O]]]]]] { def f[x] =
        (segs, f, stack) => segs.uncons match {
          case None => Scope.pure(stack) flatMap (step)
          case Some((hd, segs)) => hd match {
            case Segment.Emit(chunk) => f match {
              case Left(f) =>
                val stack2 = stack.pushZppend(Stream2.segments(segs).map(f))
                step(try { stack2.pushEmit(chunk.map(f)) } catch { case e: Throwable => stack2.pushFail(e) })
              case Right(f) => chunk.uncons match {
                case None => step(stack.pushBind(f).pushSegments(segs))
                case Some((hd,tl)) => step {
                  stack.pushZppend(Stream2.segments(segs.push(Segment.Emit(tl))).flatMap(f))
                       .pushZppend(Try(f(hd)))
                }
              }
            }
            case Segment.Zppend(s) => s.push(stack.pushBindOrMap(f).pushSegments(segs)) flatMap (step)
            case Segment.Fail(err) => Stack.fail[F,O0](segs)(err) match {
              case Left(err) => step(stack.pushFail(err))
              case Right((s, segs)) => step(stack.pushBindOrMap(f).pushSegments(segs).pushZppend(s))
            }
            case Segment.Handler(_) => step(stack.pushBindOrMap(f).pushSegments(segs))
          }
        }
      }
    )
  }

  def segment[F[_],O](s: Segment[F,O]): Stream2[F,O] = new Stream2[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = Scope.pure { stack push s }
  }
  def segments[F[_],O](s: Catenable[Segment[F,O]]): Stream2[F,O] = new Stream2[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = Scope.pure { stack pushSegments s }
  }

  def scope[F[_],O](s: Stream2[F,O]): Stream2[F,O] = Stream2.evalScope(Scope.snapshot).flatMap { tokens =>
    s onComplete {
      // release any newly acquired resources since the snapshot taken before starting `s`
      Stream2.evalScope(Scope.newSince(tokens)) flatMap { acquired =>
        Stream2.evalScope(Scope.release(acquired)).drain
      }
    }
  }

  def acquire[F[_],R](r: F[R], cleanup: R => Free[F,Unit]): Stream2[F,R] = Stream2.suspend {
    val token = new Token()
    Stream2.evalScope(Scope.startAcquire(token)) flatMap { _ =>
      Stream2.eval(r)
             .onError { e => Stream2.evalScope(Scope.cancelAcquire(token)) flatMap { _ => Stream2.fail(e) }}
             .flatMap { r =>
               Stream2.evalScope(Scope.finishAcquire(token, cleanup(r)))
               .flatMap { _ => Stream2.emit(r).onComplete(Stream2.release(List(token)).drain) }
             }
    }
  }

  private[fs2]
  def release[F[_]](tokens: List[Token]): Stream2[F,Unit] = ???

  // def ask[F[_]]: Stream2[F,Env[F]] = evalScope(Scope.ask[F])
  def evalScope[F[_],O](s: Scope[F,O]): Stream2[F,O] = new Stream2[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = s map { o => stack push (Segment.Emit(Chunk.singleton(o))) }
  }
  def chunk[F[_],O](c: Chunk[O]): Stream2[F,O] = segment(Segment.Emit(c))
  def emit[F[_],O](w: O): Stream2[F,O] = chunk(Chunk.singleton(w))
  def empty[F[_],O]: Stream2[F,O] = chunk(Chunk.empty)
  def fail[F[_],O](err: Throwable): Stream2[F,O] = segment(Segment.Fail(err))
  def attemptEval[F[_],O](f: F[O]): Stream2[F,Either[Throwable,O]] = new Stream2[F,Either[Throwable,O]] {
    type O0 = Either[Throwable,O]
    def push[O2](stack: Stack[F,Either[Throwable,O],O2]) =
      Scope.attemptEval(f) map { o => stack push Segment.Emit(Chunk.singleton(o)) }
  }
  def eval[F[_],O](f: F[O]): Stream2[F,O] = attemptEval(f) flatMap { _ fold(fail, emit) }

  def zppend[F[_],O](s: Stream2[F,O], s2: Stream2[F,O]): Stream2[F,O] = new Stream2[F,O] {
    type O0 = s.O0
    def push[O2](stack: Stack[F,O,O2]) =
      s.push { stack push Segment.Zppend(s2) }
  }
  def suspend[F[_],O](s: => Stream2[F,O]): Stream2[F,O] = emit(()) flatMap { _ =>
    try s
    catch { case e: Throwable => fail(e) }
  }

  sealed trait Segment[F[_],O1]
  object Segment {
    case class Fail[F[_],O1](err: Throwable) extends Segment[F,O1]
    case class Emit[F[_],O1](c: Chunk[O1]) extends Segment[F,O1]
    case class Handler[F[_],O1](h: Throwable => Stream2[F,O1]) extends Segment[F,O1]
    case class Zppend[F[_],O1](s: Stream2[F,O1]) extends Segment[F,O1]
  }

  trait Stack[F[_],O1,O2] { self =>
    def apply[R](
      unbound: (Catenable[Segment[F,O1]], Eq[O1,O2]) => R,
      bound: H[R]
    ): R

    trait H[+R] { def f[x]: (Catenable[Segment[F,O1]], Either[O1 => x, O1 => Stream2[F,x]], Stack[F,x,O2]) => R }

    def pushHandler(f: Throwable => Stream2[F,O1]) = push(Segment.Handler(f))
    def pushEmit(s: Chunk[O1]) = push(Segment.Emit(s))
    def pushFail(e: Throwable) = push(Segment.Fail(e))
    def pushZppend(s: Stream2[F,O1]) = push(Segment.Zppend(s))
    def pushBind[O0](f: O0 => Stream2[F,O1]): Stack[F,O0,O2] = pushBindOrMap(Right(f))
    def pushMap[O0](f: O0 => O1): Stack[F,O0,O2] = pushBindOrMap(Left(f))
    def pushBindOrMap[O0](f: Either[O0 => O1, O0 => Stream2[F,O1]]): Stack[F,O0,O2] = new Stack[F,O0,O2] {
      def apply[R](unbound: (Catenable[Segment[F,O0]], Eq[O0,O2]) => R, bound: H[R]): R
      = bound.f(Catenable.empty, f, self)
    }
    def push(s: Segment[F,O1]): Stack[F,O1,O2] = self (
      (segments, eq) => Eq.subst[({type f[x] = Stack[F,O1,x] })#f, O1, O2](
                        Stack.segments(s :: segments))(eq),
      new self.H[Stack[F,O1,O2]] { def f[x] = (segments,bindf,tl) =>
        tl.pushBindOrMap(bindf).pushSegments(s :: segments)
      }
    )
    def pushSegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
      if (s.isEmpty) self
      else new Stack[F,O1,O2] {
        def apply[R](unbound: (Catenable[Segment[F,O1]], Eq[O1,O2]) => R, bound: H[R]): R
        =
        self (
          (segments, eq) => if (segments.isEmpty) unbound(s, eq) // common case
                            else unbound(s ++ segments, eq),
          new self.H[R] { def f[x] = (segments, bind, tl) =>
            bound.f(s ++ segments, bind, tl)
          }
        )
      }
  }

  object Stack {
    def empty[F[_],O1]: Stack[F,O1,O1] = segments(Catenable.empty)

    def segments[F[_],O1](s: Catenable[Segment[F,O1]]): Stack[F,O1,O1] = new Stack[F,O1,O1] {
      def apply[R](unbound: (Catenable[Segment[F,O1]], Eq[O1,O1]) => R, bound: H[R]): R
      = unbound(s, Eq.refl)
    }

    @annotation.tailrec
    def fail[F[_],O1](s: Catenable[Segment[F,O1]])(err: Throwable)
    : Either[Throwable, (Stream2[F,O1], Catenable[Segment[F,O1]])]
    = s.uncons match {
      case None => Left(err)
      case Some((Segment.Handler(f),tl)) => Right(Try(f(err)) -> tl)
      case Some((_, tl)) => fail(tl)(err)
    }
  }

  private
  def runCleanup[F[_]](l: Resources[Token,Free[F,Unit]]): Free[F,Unit] =
    l.closeAll match {
      case Some(l) => runCleanup(l)
      case None => sys.error("internal FS2 error: cannot run cleanup actions while resources are being acquired: "+l)
    }

  private
  def runCleanup[F[_]](cleanups: Iterable[Free[F,Unit]]): Free[F,Unit] =
    // note - run cleanup actions in LIFO order, later actions run first
    cleanups.foldLeft[Free[F,Unit]](Free.pure(()))(
      (tl,hd) => hd flatMap { _ => tl }
    )
}

case class Scope[+F[_],+O](get: Free[R[F]#f,O]) {
  def map[O2](f: O => O2): Scope[F,O2] = Scope(get map f)
  def flatMap[F2[x]>:F[x],O2](f: O => Scope[F2,O2]): Scope[F2,O2] =
    Scope(get flatMap[R[F2]#f,O2] (f andThen (_.get)))
  def bindEnv[F2[_]](env: Env[F2])(implicit S: Sub1[F,F2]): Free[F2,O] = Free.suspend {
    type FO[x] = Free[F2,x]
    val B = new Free.B[R[F]#f,FO,O] { def f[x] = (r,g) => r match {
      case Right(r) => g(r)
      case Left(i) => i match {
        case Stream2.RF.Eval(fx) => Free.eval(S(fx)) flatMap g
        case Stream2.RF.Interrupted => g(env.interrupted())
        case Stream2.RF.Snapshot => g(env.tracked.snapshot)
        case Stream2.RF.NewSince(tokens) => g(env.tracked.newSince(tokens))
        case Stream2.RF.Release(tokens) => env.tracked.release(tokens) match {
          // right way to handle this case - update the tokens map to run the finalizers
          // for this group when finishing the last acquire
          case None => sys.error("todo: release tokens while resources are being acquired")
          case Some(rs) =>
            rs.foldRight(Free.pure(()): Free[F2,Unit])((hd,tl) => hd flatMap { _ => tl }) flatMap g
        }
        case Stream2.RF.StartAcquire(token) => env.tracked.startAcquire(token); g(())
        case Stream2.RF.FinishAcquire(token, c) => env.tracked.finishAcquire(token, Sub1.substFree(c)); g(())
        case Stream2.RF.CancelAcquire(token) => env.tracked.cancelAcquire(token); g(())
      }
    }}
    get.fold[R[F]#f,FO,O](Free.pure, Free.fail, B)(Sub1.sub1[R[F]#f],implicitly[RealSupertype[O,O]])
  }
}

object Scope {
  def pure[F[_],O](o: O): Scope[F,O] = Scope(Free.pure(o))
  def attemptEval[F[_],O](o: F[O]): Scope[F,Either[Throwable,O]] =
    Scope(Free.attemptEval[R[F]#f,O](Stream2.RF.Eval(o)))
  def eval[F[_],O](o: F[O]): Scope[F,O] =
    attemptEval(o) flatMap { _.fold(fail, pure) }
  def fail[F[_],O](err: Throwable): Scope[F,O] =
    Scope(Free.fail(err))
  def interrupted[F[_]]: Scope[F,Boolean] =
    Scope(Free.eval[R[F]#f,Boolean](Stream2.RF.Interrupted))
  def snapshot[F[_]]: Scope[F,Set[Token]] =
    Scope(Free.eval[R[F]#f,Set[Token]](Stream2.RF.Snapshot))
  def newSince[F[_]](snapshot: Set[Token]): Scope[F,List[Token]] =
    Scope(Free.eval[R[F]#f,List[Token]](Stream2.RF.NewSince(snapshot)))
  def release[F[_]](tokens: List[Token]): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](Stream2.RF.Release(tokens)))
  def startAcquire[F[_]](token: Token): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](Stream2.RF.StartAcquire(token)))
  def finishAcquire[F[_]](token: Token, cleanup: Free[F,Unit]): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](Stream2.RF.FinishAcquire(token, cleanup)))
  def cancelAcquire[F[_]](token: Token): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](Stream2.RF.CancelAcquire(token)))
}
