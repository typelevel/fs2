package fs2

import fs2.internal.Resources
import fs2.util.{Catenable,Eq,Free,Sub1,~>,RealSupertype}
import StreamCore.{Env,Stack,Token}

sealed trait StreamCore[F[_],O] { self =>
  type O0

  private[fs2]
  def push[O2](stack: Stack[F,O,O2]): Scope[F,Stack[F,O0,O2]]

  // proof that this is sound - `translate`
  def covary[F2[_]](implicit S: Sub1[F,F2]): StreamCore[F2,O] =
    self.asInstanceOf[StreamCore[F2,O]]
  // proof that this is sound - `mapChunks`
  def covaryOutput[O2>:O](implicit T: RealSupertype[O,O2]): StreamCore[F,O2] =
    self.asInstanceOf[StreamCore[F,O2]]

  def flatMap[O2](f: O => StreamCore[F,O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[O3](stack: Stack[F,O2,O3]) = self.push { stack pushBind f }
    }
  def map[O2](f: O => O2): StreamCore[F,O2] = mapChunks(_ map f)
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[O3](stack: Stack[F,O2,O3]) = self.push { stack pushMap f }
    }
  def onError(f: Throwable => StreamCore[F,O]): StreamCore[F,O] = new StreamCore[F,O] { type O0 = self.O0
    def push[O2](stack: Stack[F,O,O2]) = self.push { stack pushHandler f }
  }

  def translate[G[_]](f: F ~> G): StreamCore[G,O] = new StreamCore[G,O] {
    type O0 = self.O0
    def push[O2](stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]] =
      ???
      // self.push(stack).translate(f).map(_.translate)
  }

  def maskErrors: StreamCore[F,O] = self.onError(_ => StreamCore.empty)
  def drain[O2]: StreamCore[F,O2] = self.flatMap(_ => StreamCore.empty)

  def onComplete(s2: StreamCore[F,O]): StreamCore[F,O] =
    StreamCore.append(self onError (e => StreamCore.append(s2, StreamCore.fail(e))), s2)

  def step: Scope[F, Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
    = push(Stack.empty[F,O]) flatMap (StreamCore.step)

  def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldScope(z)(f).bindEnv(Env(Resources.empty[Token,Free[F,Unit]], () => false))

  def runFoldScope[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] = step flatMap {
    case None => Scope.pure(z)
    case Some(Left(err)) => Scope.fail(err)
    case Some(Right(Step(hd,tl))) =>
      try tl.runFoldScope(hd.foldLeft(z)(f))(f)
      catch { case e: Throwable => Scope.fail(e) }
  }

  def uncons: StreamCore[F, Option[Step[Chunk[O], StreamCore[F,O]]]] =
    StreamCore.evalScope(step) flatMap {
      case None => StreamCore.emit(None)
      case Some(Left(err)) => StreamCore.fail(err)
      case Some(Right(s)) => StreamCore.emit(Some(s))
    }

  def fetchAsync(implicit F: Async[F]): StreamCore[F, Async.Future[F,StreamCore[F,O]]] =
    unconsAsync map { f => f map {
      case None => StreamCore.empty
      case Some(Left(err)) => StreamCore.fail(err)
      case Some(Right(Step(hd, tl))) => StreamCore.append(StreamCore.chunk(hd), tl)
    }}

  def unconsAsync(implicit F: Async[F]): StreamCore[F,Async.Future[F,Option[Either[Throwable, Step[Chunk[O], StreamCore[F,O]]]]]] =
  StreamCore.eval(F.ref[Option[Either[Throwable, Step[Chunk[O],StreamCore[F,O]]]]]).flatMap { ref =>
    val token = new Token()
    val resources = Resources.empty[Token,Free[F,Unit]]
    val interrupt = new java.util.concurrent.atomic.AtomicBoolean(false)
    val rootCleanup = Free.suspend { resources.closeAll match {
      case None =>
        Free.eval(F.get(ref)) flatMap { _ =>
          resources.closeAll match {
            case None => Free.pure(())
            case Some(resources) => StreamCore.runCleanup(resources)
          }
        }
        case Some(resources) =>
          StreamCore.runCleanup(resources)
    }}
    def tweakEnv: Scope[F,Unit] =
      Scope.startAcquire(token) flatMap { _ => Scope.finishAcquire(token, rootCleanup) }
    val s: F[Unit] = F.set(ref) { step.bindEnv(StreamCore.Env(resources, () => resources.isClosed)).run }
    StreamCore.evalScope(tweakEnv).flatMap { _ =>
      StreamCore.eval(s) map { _ =>
        // todo: add appendOnForce which deallocates the root token when forced
        F.read(ref)
      }
    }
  }
}

private[fs2]
object StreamCore {

  class Token()

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
  def Try[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] =
    try s
    catch { case e: Throwable => fail(e) }

  def step[F[_],O0,O](stack: Stack[F,O0,O])
  : Scope[F,Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
  = Scope.interrupted.flatMap { interrupted =>
    if (interrupted) Scope.pure(Some(Left(Interrupted)))
    else stack (
      (segs, eq) => Eq.subst[({ type f[x] = Catenable[Segment[F,x]] })#f, O0, O](segs)(eq).uncons match {
        case None => Scope.pure(None)
        case Some((hd, segs)) => hd match {
          case Segment.Fail(err) => Stack.fail[F,O](segs)(err) match {
            case Left(err) => Scope.pure(Some(Left(err)))
            case Right((s, segs)) => step(Stack.segments(segs).pushAppend(s))
          }
          case Segment.Emit(chunk) => Scope.pure(Some(Right(Step(chunk, StreamCore.segments(segs)))))
          case Segment.Handler(h) => step(Stack.segments(segs))
          case Segment.Append(s) => s.push(Stack.segments(segs)) flatMap (step)
        }
      },
      new stack.H[Scope[F,Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]] { def f[x] =
        (segs, f, stack) => segs.uncons match {
          case None => Scope.pure(stack) flatMap (step)
          case Some((hd, segs)) => hd match {
            case Segment.Emit(chunk) => f match {
              case Left(f) =>
                val stack2 = stack.pushAppend(StreamCore.segments(segs).mapChunks(f))
                step(try { stack2.pushEmit(f(chunk)) }
                     catch { case e: Throwable => stack2.pushFail(e) })
              case Right(f) => chunk.uncons match {
                case None => step(stack.pushBind(f).pushSegments(segs))
                case Some((hd,tl)) => step {
                  stack.pushAppend(StreamCore.segments(segs.push(Segment.Emit(tl))).flatMap(f))
                       .pushAppend(Try(f(hd)))
                }
              }
            }
            case Segment.Append(s) =>
              s.push(stack.pushBindOrMap(f).pushSegments(segs)) flatMap (step)
            case Segment.Fail(err) => Stack.fail[F,O0](segs)(err) match {
              case Left(err) => step(stack.pushFail(err))
              case Right((s, segs)) =>
                step(stack.pushBindOrMap(f).pushSegments(segs).pushAppend(s))
            }
            case Segment.Handler(_) => step(stack.pushBindOrMap(f).pushSegments(segs))
          }
        }
      }
    )
  }

  def segment[F[_],O](s: Segment[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = Scope.pure { stack push s }
  }
  def segments[F[_],O](s: Catenable[Segment[F,O]]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = Scope.pure { stack pushSegments s }
  }

  def scope[F[_],O](s: StreamCore[F,O]): StreamCore[F,O] = StreamCore.evalScope(Scope.snapshot).flatMap { tokens =>
    s onComplete {
      // release any newly acquired resources since the snapshot taken before starting `s`
      StreamCore.evalScope(Scope.newSince(tokens)) flatMap { acquired =>
        StreamCore.evalScope(Scope.release(acquired)).drain
      }
    }
  }

  def acquire[F[_],R](r: F[R], cleanup: R => Free[F,Unit]): StreamCore[F,R] = StreamCore.suspend {
    val token = new Token()
    StreamCore.evalScope(Scope.startAcquire(token)) flatMap { _ =>
      StreamCore.eval(r)
             .onError { e => StreamCore.evalScope(Scope.cancelAcquire(token)) flatMap { _ => StreamCore.fail(e) }}
             .flatMap { r =>
               StreamCore.evalScope(Scope.finishAcquire(token, cleanup(r)))
               .flatMap { _ => StreamCore.emit(r).onComplete(StreamCore.release(List(token)).drain) }
             }
    }
  }

  private[fs2]
  def release[F[_]](tokens: List[Token]): StreamCore[F,Unit] = ???

  def evalScope[F[_],O](s: Scope[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[O2](stack: Stack[F,O,O2]) = s map { o => stack push (Segment.Emit(Chunk.singleton(o))) }
  }
  def chunk[F[_],O](c: Chunk[O]): StreamCore[F,O] = segment(Segment.Emit[F,O](c))
  def emit[F[_],O](w: O): StreamCore[F,O] = chunk(Chunk.singleton(w))
  def empty[F[_],O]: StreamCore[F,O] = chunk(Chunk.empty)
  def fail[F[_],O](err: Throwable): StreamCore[F,O] = segment(Segment.Fail[F,O](err))
  def attemptEval[F[_],O](f: F[O]): StreamCore[F,Either[Throwable,O]] = new StreamCore[F,Either[Throwable,O]] {
    type O0 = Either[Throwable,O]
    def push[O2](stack: Stack[F,Either[Throwable,O],O2]) =
      Scope.attemptEval(f) map { o => stack push Segment.Emit(Chunk.singleton(o)) }
  }
  def eval[F[_],O](f: F[O]): StreamCore[F,O] = attemptEval(f) flatMap { _ fold(fail, emit) }

  def append[F[_],O](s: StreamCore[F,O], s2: StreamCore[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = s.O0
    def push[O2](stack: Stack[F,O,O2]) =
      s.push { stack push Segment.Append(s2) }
  }
  def suspend[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] = emit(()) flatMap { _ => s }

  sealed trait Segment[F[_],O1]
  object Segment {
    case class Fail[F[_],O1](err: Throwable) extends Segment[F,O1]
    case class Emit[F[_],O1](c: Chunk[O1]) extends Segment[F,O1]
    case class Handler[F[_],O1](h: Throwable => StreamCore[F,O1]) extends Segment[F,O1]
    case class Append[F[_],O1](s: StreamCore[F,O1]) extends Segment[F,O1]
  }

  trait Stack[F[_],O1,O2] { self =>
    def apply[R](
      unbound: (Catenable[Segment[F,O1]], Eq[O1,O2]) => R,
      bound: H[R]
    ): R

    trait H[+R] { def f[x]: (Catenable[Segment[F,O1]], Either[Chunk[O1] => Chunk[x], O1 => StreamCore[F,x]], Stack[F,x,O2]) => R }

    def pushHandler(f: Throwable => StreamCore[F,O1]) = push(Segment.Handler(f))
    def pushEmit(s: Chunk[O1]) = push(Segment.Emit(s))
    def pushFail(e: Throwable) = push(Segment.Fail(e))
    def pushAppend(s: StreamCore[F,O1]) = push(Segment.Append(s))
    def pushBind[O0](f: O0 => StreamCore[F,O1]): Stack[F,O0,O2] = pushBindOrMap(Right(f))
    def pushMap[O0](f: Chunk[O0] => Chunk[O1]): Stack[F,O0,O2] = pushBindOrMap(Left(f))
    def pushBindOrMap[O0](f: Either[Chunk[O0] => Chunk[O1], O0 => StreamCore[F,O1]]): Stack[F,O0,O2] = new Stack[F,O0,O2] {
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
    : Either[Throwable, (StreamCore[F,O1], Catenable[Segment[F,O1]])]
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

