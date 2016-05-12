package fs2

import fs2.internal.Resources
import fs2.util.{Catenable,Eq,Free,Sub1,~>,RealSupertype}
import StreamCore.{Env,NT,Stack,Token}

private[fs2]
sealed trait StreamCore[F[_],O] { self =>
  type O0

  def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]]
  def pushEmit(c: Chunk[O]): StreamCore[F,O] = StreamCore.append(StreamCore.chunk(c), self)

  def render: String

  final override def toString = render

  def attempt: StreamCore[F,Either[Throwable,O]] =
    self.map(a => Right(a): Either[Throwable,O]).onError(e => StreamCore.emit(Left(e)))

  def translate[G[_]](f: NT[F,G]): StreamCore[G,O] =
    f.same.fold(sub => Sub1.substStreamCore(self)(sub), f => {
      new StreamCore[G,O] {
        type O0 = self.O0
        def push[G2[_],O2](u: NT[G,G2], stack: Stack[G2,O,O2]): Scope[G2,Stack[G2,O0,O2]] =
          self.push(NT.T(f) andThen u, stack)
        def render = s"$self.translate($f)"
      }
    })

  // proof that this is sound - `translate`
  def covary[F2[_]](implicit S: Sub1[F,F2]): StreamCore[F2,O] =
    self.asInstanceOf[StreamCore[F2,O]]
  // proof that this is sound - `mapChunks`
  def covaryOutput[O2>:O](implicit T: RealSupertype[O,O2]): StreamCore[F,O2] =
    self.asInstanceOf[StreamCore[F,O2]]

  def flatMap[O2](f: O => StreamCore[F,O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        Scope.suspend { self.push(u, stack pushBind NT.convert(f)(u)) }
      def render = s"$self.flatMap($f)"
    }
  def map[O2](f: O => O2): StreamCore[F,O2] = mapChunks(_ map f)
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        self.push(u, stack pushMap f)
      def render = s"$self.mapChunks($f)"
    }
  def onError(f: Throwable => StreamCore[F,O]): StreamCore[F,O] =
    new StreamCore[F,O] { type O0 = self.O0
      def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
        Scope.suspend { self.push(u, stack pushHandler NT.convert(f)(u)) }
      def render = s"$self.onError($f)"
    }

  def maskErrors: StreamCore[F,O] = self.onError(_ => StreamCore.empty)
  def drain[O2]: StreamCore[F,O2] = self.flatMap(_ => StreamCore.empty)

  def onComplete(s2: StreamCore[F,O]): StreamCore[F,O] =
    StreamCore.append(self onError (e => StreamCore.append(s2, StreamCore.fail(e))), s2)

  def step: Scope[F, Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
    = push(NT.Id(), Stack.empty[F,O]) flatMap (StreamCore.step)

  def stepTrace(t: Trace): Scope[F, Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
    = push(NT.Id(), Stack.empty[F,O]) flatMap (StreamCore.stepTrace(t))

  def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldTrace(Trace.Off)(z)(f)

  def runFoldTrace[O2](t: Trace)(z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldScopeTrace(t)(z)(f)
      .bindEnv(Env(Resources.empty[Token,Free[F,Either[Throwable,Unit]]], () => false))
      .map(_._2)

  def runFoldScope[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] =
    runFoldScopeTrace(Trace.Off)(z)(f)

  def runFoldScopeTrace[O2](t: Trace)(z: O2)(f: (O2,O) => O2): Scope[F,O2] = stepTrace(t) flatMap {
    case None => Scope.pure(z)
    case Some(Left(err)) => Scope.fail(err)
    case Some(Right(Step(hd,tl))) =>
      try tl.runFoldScopeTrace(t)(hd.foldLeft(z)(f))(f)
      catch { case e: Throwable => Scope.fail(e) }
  }

  def uncons: StreamCore[F, Option[Step[Chunk[O], StreamCore[F,O]]]] =
    StreamCore.evalScope(step) flatMap {
      case None => StreamCore.emit(None)
      case Some(Left(err)) => StreamCore.fail(err)
      case Some(Right(s)) => StreamCore.emit(Some(s))
    }

  def fetchAsync(implicit F: Async[F]): Scope[F, Async.Future[F,StreamCore[F,O]]] =
    unconsAsync map { f => f map { case (leftovers,o) =>
      val inner: StreamCore[F,O] = o match {
        case None => StreamCore.empty
        case Some(Left(err)) => StreamCore.fail(err)
        case Some(Right(Step(hd, tl))) => StreamCore.append(StreamCore.chunk(hd), tl)
      }
      if (leftovers.isEmpty) inner else StreamCore.release(leftovers) flatMap { _ => inner }
    }}

  def unconsAsync(implicit F: Async[F])
  : Scope[F,Async.Future[F, (List[Token], Option[Either[Throwable, Step[Chunk[O],StreamCore[F,O]]]])]]
  = Scope.eval(F.ref[(List[Token], Option[Either[Throwable, Step[Chunk[O],StreamCore[F,O]]]])]).flatMap { ref =>
    val token = new Token()
    val resources = Resources.emptyNamed[Token,Free[F,Either[Throwable,Unit]]]("unconsAsync")
    val noopWaiters = scala.collection.immutable.Stream.continually(() => ())
    lazy val rootCleanup: Free[F,Either[Throwable,Unit]] = Free.suspend { resources.closeAll(noopWaiters) match {
      case Left(waiting) =>
        Free.eval(F.traverse(Vector.fill(waiting)(F.ref[Unit]))(identity)) flatMap { gates =>
          resources.closeAll(gates.toStream.map(gate => () => F.runSet(gate)(Right(())))) match {
            case Left(_) => Free.eval(F.traverse(gates)(F.get)) flatMap { _ =>
              resources.closeAll(noopWaiters) match {
                case Left(_) => println("likely FS2 bug - resources still being acquired after Resources.closeAll call")
                                rootCleanup
                case Right(resources) => StreamCore.runCleanup(resources.map(_._2))
              }
            }
            case Right(resources) =>
              StreamCore.runCleanup(resources.map(_._2))
          }
        }
        case Right(resources) =>
          StreamCore.runCleanup(resources.map(_._2))
    }}
    def tweakEnv: Scope[F,Unit] =
      Scope.startAcquire(token) flatMap { _ => Scope.finishAcquire(token, rootCleanup) }
    val s: F[Unit] = F.set(ref) { step.bindEnv(StreamCore.Env(resources, () => resources.isClosed)).run }
    tweakEnv.flatMap { _ =>
      Scope.eval(s) map { _ =>
        F.read(ref).appendOnForce { Scope.suspend {
          // Important: copy any locally acquired resources to our parent and remove the placeholder
          // root token, which only needed if the parent terminated early, before the future was forced
          val removeRoot = Scope.release(List(token)) flatMap { _.fold(Scope.fail, Scope.pure) }
          (resources.closeAll(scala.collection.immutable.Stream()) match {
            case Left(_) => Scope.fail(new IllegalStateException("FS2 bug: resources still being acquired"))
            case Right(rs) => removeRoot flatMap { _ => Scope.traverse(rs) {
              case (token,r) => Scope.acquire(token,r)
            }}
          }) flatMap { (rs: List[Either[Throwable,Unit]]) =>
            rs.collect { case Left(e) => e } match {
              case Nil => Scope.pure(())
              case e :: _ => Scope.fail(e)
            }
          }
        }}
      }
    }
  }
}

private[fs2]
object StreamCore {

  class Token() {
    override def toString = s"Token(${##})"
  }

  case class Env[F[_]](tracked: Resources[Token,Free[F,Either[Throwable,Unit]]], interrupted: () => Boolean)

  trait R[F[_]] { type f[x] = RF[F,x] }

  sealed trait RF[+F[_],+A]
  object RF {
    case class Eval[F[_],A](f: F[A]) extends RF[F,A]
    case object Interrupted extends RF[Nothing,Boolean]
    case object Snapshot extends RF[Nothing,Set[Token]]
    case class NewSince(snapshot: Set[Token]) extends RF[Nothing,List[Token]]
    case class Release(tokens: List[Token]) extends RF[Nothing,Either[Throwable,Unit]]
    case class StartAcquire(token: Token) extends RF[Nothing,Boolean]
    case class FinishAcquire[F[_]](token: Token, cleanup: Free[F,Either[Throwable,Unit]]) extends RF[F,Unit]
    case class CancelAcquire(token: Token) extends RF[Nothing,Unit]
  }

  sealed trait NT[-F[_],+G[_]] {
    val same: Either[Sub1[F,G], F ~> G]
    def andThen[H[_]](f: NT[G,H]): NT[F,H]
    def apply[A](f: F[A]): G[A]
  }

  object NT {
    case class Id[F[_]]() extends NT[F,F] {
      val same = Left(Sub1.sub1[F])
      def andThen[H[_]](f: NT[F,H]): NT[F,H] = f
      def apply[A](f: F[A]): F[A] = f
    }
    case class T[F[_],G[_]](u: F ~> G) extends NT[F,G] {
      val same = Right(u)
      def apply[A](f: F[A]): G[A] = u(f)
      def andThen[H[_]](f: NT[G,H]): NT[F,H] = f.same.fold(
        f => T(Sub1.substUF1(u)(f)),
        f => T(u andThen f)
      )
    }
    def convert[F[_],G[_],O](s: StreamCore[F,O])(u: NT[F,G]): StreamCore[G,O] =
      u.same match {
        case Left(sub) => Sub1.substStreamCore(s)(sub)
        case Right(u) => s.translate(NT.T(u))
      }
    def convert[F[_],G[_],O1,O2](f: O1 => StreamCore[F,O2])(u: NT[F,G]): O1 => StreamCore[G,O2] =
      u.same match {
        case Left(sub) => Sub1.substStreamCoreF(f)(sub)
        case Right(u) => o1 => f(o1).translate(NT.T(u))
      }
    def convert[F[_],G[_],O](s: Segment[F,O])(u: NT[F,G]): Segment[G,O] =
      u.same match {
        case Left(sub) => Sub1.substSegment(s)(sub)
        case Right(u) => s.translate(NT.T(u))
      }
    def convert[F[_],G[_],O](s: Catenable[Segment[F,O]])(u: NT[F,G]): Catenable[Segment[G,O]] = {
      type f[g[_],x] = Catenable[Segment[g,x]]
      u.same match {
        case Left(sub) => Sub1.subst[f,F,G,O](s)(sub)
        case Right(_) => s.map(_ translate u)
      }
    }
    def convert[F[_],G[_],O](s: Scope[F,O])(u: NT[F,G]): Scope[G,O] =
      u.same match {
        case Left(sub) => Sub1.subst[Scope,F,G,O](s)(sub)
        case Right(u) => s translate u
      }
  }

  case object Interrupted extends Exception { override def fillInStackTrace = this }

  private[fs2]
  def Try[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] =
    try s
    catch { case e: Throwable => fail(e) }

  def step[F[_],O0,O](stack: Stack[F,O0,O])
  : Scope[F,Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
  = stepTrace(Trace.Off)(stack)

  def stepTrace[F[_],O0,O](trace: Trace)(stack: Stack[F,O0,O])
  : Scope[F,Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]
  = Scope.interrupted.flatMap { interrupted =>
    if (interrupted) Scope.pure(Some(Left(Interrupted)))
    else {
      if (trace.enabled) trace {
        "Stepping stack:\n" + stack.render.zipWithIndex.map { case (entry, idx) => s"  $idx: $entry" }.mkString("", "\n", "\n")
      }
      stack (
        (segs, eq) => Eq.subst[({ type f[x] = Catenable[Segment[F,x]] })#f, O0, O](segs)(eq).uncons match {
          case None => Scope.pure(None)
          case Some((hd, segs)) => hd match {
            case Segment.Fail(err) => Stack.fail[F,O](segs)(err) match {
              case Left(err) => Scope.pure(Some(Left(err)))
              case Right((s, segs)) => stepTrace(trace)(Stack.segments(segs).pushAppend(s))
            }
            case Segment.Emit(chunk) => Scope.pure(Some(Right(Step(chunk, StreamCore.segments(segs)))))
            case Segment.Handler(h) => stepTrace(trace)(Stack.segments(segs))
            case Segment.Append(s) => s.push(NT.Id(), Stack.segments(segs)) flatMap stepTrace(trace)
          }
        },
        new stack.H[Scope[F,Option[Either[Throwable,Step[Chunk[O],StreamCore[F,O]]]]]] { def f[x] =
          (segs, f, stack) => { segs.uncons match {
            case None => stepTrace(trace)(stack)
            case Some((hd, segs)) => hd match {
              case Segment.Emit(chunk) => f match {
                case Left(f) =>
                  val segs2 = segs.map(_.mapChunks(f))
                  val stack2 = stack.pushSegments(segs2)
                  stepTrace(trace)(try { stack2.pushEmit(f(chunk)) }
                       catch { case e: Throwable => stack2.pushFail(e) })
                case Right(f) => chunk.uncons match {
                  case None => stepTrace(trace)(stack.pushBind(f).pushSegments(segs))
                  case Some((hd,tl)) => stepTrace(trace)({
                    val segs2: Catenable[Segment[F, x]] =
                      (if (tl.isEmpty) segs else segs.push(Segment.Emit(tl))).map(_.interpretBind(f))
                    val stack2 = stack.pushSegments(segs2)
                    try stack2.pushAppend(f(hd))
                    catch {
                      case t: Throwable => stack2.pushFail(t)
                    }
                  })
                }
              }
              case Segment.Append(s) =>
                s.push(NT.Id(), stack.pushBindOrMap(f).pushSegments(segs)) flatMap stepTrace(trace)
              case Segment.Fail(err) => Stack.fail[F,O0](segs)(err) match {
                case Left(err) => stepTrace(trace)(stack.pushFail(err))
                case Right((s, segs)) =>
                  stepTrace(trace)(stack.pushBindOrMap(f).pushSegments(segs).pushAppend(s))
              }
              case Segment.Handler(_) => stepTrace(trace)(stack.pushBindOrMap(f).pushSegments(segs))
            }
          }
        }}
    )}
  }

  def segment[F[_],O](s: Segment[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack push (NT.convert(s)(u)) }
    def render = s"Segment($s)"
  }
  def segments[F[_],O](s: Catenable[Segment[F,O]]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack pushSegments (NT.convert(s)(u)) }
    def render = "Segments(" + s.toStream.toList.mkString(", ") + ")"
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
      StreamCore.attemptEval(r).flatMap {
        case Left(e) => StreamCore.evalScope(Scope.cancelAcquire(token)) flatMap { _ => StreamCore.fail(e) }
        case Right(r) =>
          StreamCore.evalScope(Scope.finishAcquire(token, cleanup(r).attempt))
                    .flatMap { _ => StreamCore.emit(r).onComplete(StreamCore.release(List(token)).drain) }
      }
    }
  }

  private[fs2]
  def release[F[_]](tokens: List[Token]): StreamCore[F,Unit] =
    evalScope(Scope.release(tokens)) flatMap { _ fold(fail, emit) }

  def evalScope[F[_],O](s: Scope[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      NT.convert(s)(u) map { o => stack push (Segment.Emit(Chunk.singleton(o))) }
    def render = s"evalScope($s)"
  }

  def chunk[F[_],O](c: Chunk[O]): StreamCore[F,O] = segment(Segment.Emit[F,O](c))
  def emit[F[_],O](w: O): StreamCore[F,O] = chunk(Chunk.singleton(w))
  def empty[F[_],O]: StreamCore[F,O] = chunk(Chunk.empty)
  def fail[F[_],O](err: Throwable): StreamCore[F,O] = segment(Segment.Fail[F,O](err))
  def attemptEval[F[_],O](f: F[O]): StreamCore[F,Either[Throwable,O]] = new StreamCore[F,Either[Throwable,O]] {
    type O0 = Either[Throwable,O]
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,Either[Throwable,O],O2]) =
      Scope.attemptEval(u(f)) map { o => stack push Segment.Emit(Chunk.singleton(o)) }
    def render = s"attemptEval($f)"
  }
  def eval[F[_],O](f: F[O]): StreamCore[F,O] = attemptEval(f) flatMap { _ fold(fail, emit) }

  def append[F[_],O](s: StreamCore[F,O], s2: StreamCore[F,O]): StreamCore[F,O] =
    new StreamCore[F,O] {
      type O0 = s.O0
      def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
        Scope.suspend { s.push(u, stack push Segment.Append(s2 translate u)) }
       def render = s"append($s, $s2)"
    }
  def suspend[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] = emit(()) flatMap { _ => s }

  sealed trait Segment[F[_],O1] {
    import Segment._

    def translate[G[_]](u: NT[F,G]): Segment[G,O1] = u.same match {
      case Left(sub) => Sub1.substSegment(this)(sub)
      case Right(uu) => this match {
        case Append(s) => Append(s translate u)
        case Handler(h) => Handler(NT.convert(h)(u))
        case Emit(c) => Emit(c)
        case Fail(e) => Fail(e)
      }
    }

    def mapChunks[O2](f: Chunk[O1] => Chunk[O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.mapChunks(f))
      case Handler(h) => Handler(t => h(t).mapChunks(f))
      case Emit(c) => Emit(f(c))
      case Fail(e) => Fail(e)
    }

    def interpretBind[O2](f: O1 => StreamCore[F, O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.flatMap(f))
      case Handler(h) => Handler(t => h(t).flatMap(f))
      case Emit(c) => if (c.isEmpty) Emit(Chunk.empty) else Append(c.toVector.map(o => Try(f(o))).reduceRight((s, acc) => StreamCore.append(s, acc)))
      case Fail(e) => Fail(e)
    }
  }
  object Segment {
    case class Fail[F[_],O1](err: Throwable) extends Segment[F,O1]
    case class Emit[F[_],O1](c: Chunk[O1]) extends Segment[F,O1]
    case class Handler[F[_],O1](h: Throwable => StreamCore[F,O1]) extends Segment[F,O1] {
      override def toString = s"Handler(h#${System.identityHashCode(h)})"
    }
    case class Append[F[_],O1](s: StreamCore[F,O1]) extends Segment[F,O1]
  }

  trait Stack[F[_],O1,O2] { self =>
    def apply[R](
      unbound: (Catenable[Segment[F,O1]], Eq[O1,O2]) => R,
      bound: H[R]
    ): R

    def render: List[String]

    trait H[+R] { def f[x]: (Catenable[Segment[F,O1]], Either[Chunk[O1] => Chunk[x], O1 => StreamCore[F,x]], Stack[F,x,O2]) => R }

    def pushHandler(f: Throwable => StreamCore[F,O1]) = push(Segment.Handler(f))
    def pushEmit(s: Chunk[O1]) = push(Segment.Emit(s))
    def pushFail(e: Throwable) = push(Segment.Fail(e))
    def pushAppend(s: StreamCore[F,O1]) = push(Segment.Append(s))
    def pushBind[O0](f: O0 => StreamCore[F,O1]): Stack[F,O0,O2] = pushBindOrMap(Right(f))
    def pushMap[O0](f: Chunk[O0] => Chunk[O1]): Stack[F,O0,O2] = pushBindOrMap(Left(f))
    def pushBindOrMap[O0](f: Either[Chunk[O0] => Chunk[O1], O0 => StreamCore[F,O1]]): Stack[F,O0,O2] = new Stack[F,O0,O2] {
      def render = f.fold(ff => s"Map(f#${System.identityHashCode(ff)})",
                          ff => s"Bind(f#${System.identityHashCode(ff)})") :: self.render
      def apply[R](unbound: (Catenable[Segment[F,O0]], Eq[O0,O2]) => R, bound: H[R]): R
      = bound.f(Catenable.empty, f, self)
    }
    def push(s: Segment[F,O1]): Stack[F,O1,O2] = s match {
      case Segment.Emit(c) if c.isEmpty => this
      case _ => self (
        (segments, eq) => Eq.subst[({type f[x] = Stack[F,O1,x] })#f, O1, O2](
                          Stack.segments(s :: segments))(eq),
        new self.H[Stack[F,O1,O2]] { def f[x] = (segments,bindf,tl) =>
          tl.pushBindOrMap(bindf).pushSegments(s :: segments)
        }
      )
    }
    def pushSegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
      if (s.isEmpty) self
      else new Stack[F,O1,O2] {
        def render = Stack.describeSegments(s) :: self.render
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
        def render = List(describeSegments(s))
      def apply[R](unbound: (Catenable[Segment[F,O1]], Eq[O1,O1]) => R, bound: H[R]): R
      = unbound(s, Eq.refl)
    }

    private[fs2]
    def describeSegments[F[_],O](s: Catenable[Segment[F,O]]): String = {
      val segments = s.toStream.toList
      s"Segments (${segments.size})\n"+segments.zipWithIndex.map { case (s, idx) => s"    s$idx: $s" }.mkString("\n")
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
  def runCleanup[F[_]](l: Resources[Token,Free[F,Either[Throwable,Unit]]]): Free[F,Either[Throwable,Unit]] =
    l.closeAll(scala.collection.immutable.Stream()) match {
      case Right(l) => runCleanup(l.map(_._2))
      case Left(_) => sys.error("internal FS2 error: cannot run cleanup actions while resources are being acquired: "+l)
    }

  private[fs2]
  def runCleanup[F[_]](cleanups: Iterable[Free[F,Either[Throwable,Unit]]]): Free[F,Either[Throwable,Unit]] = {
    // note - run cleanup actions in FIFO order, but avoid left-nesting binds
    // all actions are run but only first error is reported
    cleanups.toList.reverse.foldLeft[Free[F,Either[Throwable,Unit]]](Free.pure(Right(())))(
      (tl,hd) => hd flatMap { _.fold(e => tl flatMap { _ => Free.pure(Left(e)) }, _ => tl) }
    )
  }
}
