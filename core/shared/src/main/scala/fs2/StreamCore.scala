package fs2

import fs2.internal.Resources
import fs2.util.{Attempt,Catenable,Concurrent,Free,NonFatal,Sub1,RealSupertype,UF1}
import StreamCore.{Env,NT,Stack,Token}

import cats.effect.IO
import cats.implicits._

private[fs2] sealed trait StreamCore[F[_],O] { self =>
  type O0

  def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]): Scope[G,Stack[G,O0,O2]]
  final def pushEmit(c: Chunk[O]): StreamCore[F,O] = if (c.isEmpty) this else StreamCore.append(StreamCore.chunk(c), self)

  def render: String

  final override def toString = render

  final def attempt: StreamCore[F,Attempt[O]] =
    self.map(a => Right(a): Attempt[O]).onError(e => StreamCore.emit(Left(e)))

  final def translate[G[_]](f: NT[F,G]): StreamCore[G,O] =
    f.same.fold(sub => Sub1.substStreamCore(self)(sub), f => {
      new StreamCore[G,O] {
        type O0 = self.O0
        def push[G2[_],O2](u: NT[G,G2], stack: Stack[G2,O,O2]): Scope[G2,Stack[G2,O0,O2]] =
          self.push(NT.T(f) andThen u, stack)
        def render = s"$self.translate($f)"
      }
    })

  // proof that this is sound - `translate`
  final def covary[F2[_]](implicit S: Sub1[F,F2]): StreamCore[F2,O] =
    self.asInstanceOf[StreamCore[F2,O]]
  // proof that this is sound - `mapChunks`
  final def covaryOutput[O2>:O](implicit T: RealSupertype[O,O2]): StreamCore[F,O2] =
    self.asInstanceOf[StreamCore[F,O2]]

  final def flatMap[O2](f: O => StreamCore[F,O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        Scope.suspend { self.push(u, stack pushBind NT.convert(f)(u)) }
      def render = s"$self.flatMap(<function1>)"
    }
  final def map[O2](f: O => O2): StreamCore[F,O2] = mapChunks(_ map f)
  final def mapChunks[O2](f: Chunk[O] => Chunk[O2]): StreamCore[F,O2] =
    new StreamCore[F,O2] { type O0 = self.O0
      def push[G[_],O3](u: NT[F,G], stack: Stack[G,O2,O3]) =
        self.push(u, stack pushMap f)
      def render = s"$self.mapChunks(<function1>)"
    }
  final def onError(f: Throwable => StreamCore[F,O]): StreamCore[F,O] =
    new StreamCore[F,O] { type O0 = self.O0
      def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
        Scope.suspend { self.push(u, stack pushHandler NT.convert(f)(u)) }
      def render = s"$self.onError(<function1>)"
    }

  final def drain[O2]: StreamCore[F,O2] = self.flatMap(_ => StreamCore.empty)

  final def onComplete(s2: StreamCore[F,O]): StreamCore[F,O] =
    StreamCore.append(self onError (e => StreamCore.append(s2, StreamCore.fail(e))), s2)

  final def step: Scope[F, StreamCore.StepResult[F,O]]
    = push(NT.Id(), Stack.empty[F,O]).flatMap(_.step)

  final def runFold[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    runFoldScope(z)(f)
      .bindEnv(Env(Resources.empty[Token,Free[F,Attempt[Unit]]], () => false))
      .map(_._2)

  final def runFoldScope[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] =
    // note, establish a root scope, otherwise unwrapped use of uncons can
    // lead to finalizers not being run if the uncons'd streams aren't fully traversed
    StreamCore.scope(this).runFoldScopeImpl(z)(f)

  private final def runFoldScopeImpl[O2](z: O2)(f: (O2,O) => O2): Scope[F,O2] = step flatMap {
    case StreamCore.StepResult.Done => Scope.pure(z)
    case StreamCore.StepResult.Failed(err) => Scope.fail(err)
    case StreamCore.StepResult.Emits(hd,tl) =>
      try tl.runFoldScopeImpl(hd.foldLeft(z)(f))(f)
      catch { case NonFatal(e) => Scope.fail(e) }
  }

  final def uncons: StreamCore[F, Option[(NonEmptyChunk[O], StreamCore[F,O])]] =
    StreamCore.evalScope(step) flatMap {
      case StreamCore.StepResult.Done => StreamCore.emit(None)
      case StreamCore.StepResult.Failed(err) => StreamCore.fail(err)
      case StreamCore.StepResult.Emits(out, s) => StreamCore.emit(Some((out, s)))
    }

  final def fetchAsync(implicit F: Concurrent[F]): Scope[F, ScopedFuture[F,StreamCore[F,O]]] =
    unconsAsync map { f => f map { case (leftovers,o) =>
      val inner: StreamCore[F,O] = o match {
        case None => StreamCore.empty
        case Some(Left(err)) => StreamCore.fail(err)
        case Some(Right((hd, tl))) => StreamCore.append(StreamCore.chunk(hd), tl)
      }
      if (leftovers.isEmpty) inner else StreamCore.release(leftovers) flatMap { _ => inner }
    }}

  final def unconsAsync(implicit F: Concurrent[F])
  : Scope[F,ScopedFuture[F, (List[Token], Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]])]]
  = Scope.eval(F.ref[(List[Token], Option[Attempt[(NonEmptyChunk[O],StreamCore[F,O])]])]).flatMap { ref =>
    val token = new Token()
    val resources = Resources.emptyNamed[Token,Free[F,Attempt[Unit]]]("unconsAsync")
    val noopWaiters = scala.collection.immutable.Stream.continually(() => ())
    lazy val rootCleanup: Free[F,Attempt[Unit]] = Free.suspend { resources.closeAll(noopWaiters) match {
      case Left(waiting) =>
        Free.eval(Vector.fill(waiting)(F.ref[Unit]).sequence) flatMap { gates =>
          resources.closeAll(gates.toStream.map(gate => () => F.unsafeRunAsync(gate.setPure(()))(_ => IO.pure(())))) match {
            case Left(_) => Free.eval(gates.traverse(_.get)) flatMap { _ =>
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
      Scope.startAcquire(token) flatMap { _ =>
      Scope.finishAcquire(token, rootCleanup) flatMap { ok =>
        if (ok) Scope.pure(())
        else Scope.evalFree(rootCleanup).flatMap(_.fold(Scope.fail, Scope.pure))
      }}
    val s: F[Unit] = ref.set { step.bindEnv(StreamCore.Env(resources, () => resources.isClosed)).run.map {
      case (ts, StreamCore.StepResult.Done) => (ts, None)
      case (ts, StreamCore.StepResult.Failed(t)) => (ts, Some(Left(t)))
      case (ts, StreamCore.StepResult.Emits(out, s)) => (ts, Some(Right((out, s))))
    } }
    tweakEnv.flatMap { _ =>
      Scope.eval(s) map { _ =>
        ScopedFuture.readRef(ref).appendOnForce { Scope.suspend {
          // Important: copy any locally acquired resources to our parent and remove the placeholder
          // root token, which only needed if the parent terminated early, before the future was forced
          val removeRoot = Scope.release(List(token)) flatMap { _.fold(Scope.fail, Scope.pure) }
          (resources.closeAll(scala.collection.immutable.Stream()) match {
            case Left(_) => Scope.fail(new IllegalStateException("FS2 bug: resources still being acquired"))
            case Right(rs) => removeRoot flatMap { _ => Scope.traverse(rs) {
              case (token,r) => Scope.acquire(token,r)
            }}
          }) flatMap { (rs: List[Attempt[Unit]]) =>
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

private[fs2] object StreamCore {

  final class Token {
    override def toString = s"Token(${##})"
  }

  final case class Env[F[_]](tracked: Resources[Token,Free[F,Attempt[Unit]]], interrupted: () => Boolean)

  trait AlgebraF[F[_]] { type f[x] = Algebra[F,x] }

  sealed trait Algebra[+F[_],+A]
  object Algebra {
    final case class Eval[F[_],A](f: F[A]) extends Algebra[F,A]
    final case object Interrupted extends Algebra[Nothing,Boolean]
    final case object Snapshot extends Algebra[Nothing,Set[Token]]
    final case class NewSince(snapshot: Set[Token]) extends Algebra[Nothing,List[Token]]
    final case class Release(tokens: List[Token]) extends Algebra[Nothing,Attempt[Unit]]
    final case class StartAcquire(token: Token) extends Algebra[Nothing,Boolean]
    final case class FinishAcquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]) extends Algebra[F,Boolean]
    final case class CancelAcquire(token: Token) extends Algebra[Nothing,Unit]
  }

  sealed trait NT[-F[_],+G[_]] {
    val same: Either[Sub1[F,G], UF1[F, G]]
    def andThen[H[_]](f: NT[G,H]): NT[F,H]
  }

  object NT {
    final case class Id[F[_]]() extends NT[F,F] {
      val same = Left(Sub1.sub1[F])
      def andThen[H[_]](f: NT[F,H]): NT[F,H] = f
    }
    final case class T[F[_],G[_]](u: UF1[F, G]) extends NT[F,G] {
      val same = Right(u)
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
    def convert[F[_],G[_],O](f: F[O])(u: NT[F,G]): G[O] =
      u.same match {
        case Left(sub) => sub(f)
        case Right(u) => u(f)
      }
  }

  final case object Interrupted extends Exception { override def fillInStackTrace = this }

  private[fs2] def attemptStream[F[_],O](s: => StreamCore[F,O]): StreamCore[F,O] =
    try s catch { case NonFatal(e) => fail(e) }

  sealed trait StepResult[+F[_], +A]
  object StepResult {
    final case object Done extends StepResult[Nothing, Nothing]
    final case class Failed(t: Throwable) extends StepResult[Nothing, Nothing]
    final case class Emits[F[_], A](out: NonEmptyChunk[A], next: StreamCore[F,A]) extends StepResult[F, A]
  }

  private def segment[F[_],O](s: Segment[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack push (NT.convert(s)(u)) }
    def render = s"Segment($s)"
  }

  private def segments[F[_],O](s: Catenable[Segment[F,O]]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      Scope.pure { stack pushSegments (NT.convert(s)(u)) }
    def render = "Segments(" + s.toList.mkString(", ") + ")"
  }

  def scope[F[_],O](s: StreamCore[F,O]): StreamCore[F,O] = StreamCore.evalScope(Scope.snapshot).flatMap { tokens =>
    s onComplete {
      // release any newly acquired resources since the snapshot taken before starting `s`
      StreamCore.evalScope(Scope.newSince(tokens)) flatMap { acquired =>
        StreamCore.evalScope(Scope.release(acquired)).drain
      }
    }
  }

  def acquire[F[_],R](r: F[R], cleanup: R => Free[F,Unit]): StreamCore[F,(Token,R)] = StreamCore.suspend {
    val token = new Token()
    StreamCore.evalScope(Scope.startAcquire(token)) flatMap { _ =>
      StreamCore.attemptEval(r).flatMap {
        case Left(e) => StreamCore.evalScope(Scope.cancelAcquire(token)) flatMap { _ => StreamCore.fail(e) }
        case Right(r) =>
          StreamCore.evalScope(Scope.finishAcquire(token, Free.suspend(cleanup(r).attempt)))
                    .flatMap { _ => StreamCore.emit((token,r)).onComplete(StreamCore.release(List(token)).drain) }
      }
    }
  }

  def release[F[_]](tokens: List[Token]): StreamCore[F,Unit] =
    evalScope(Scope.release(tokens)) flatMap { _ fold(fail, emit) }

  def evalScope[F[_],O](s: Scope[F,O]): StreamCore[F,O] = new StreamCore[F,O] {
    type O0 = O
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,O,O2]) =
      NT.convert(s)(u) map { o => stack push (Segment.Emit(Chunk.singleton(o))) }
    def render = "evalScope(<scope>)"
  }

  def chunk[F[_],O](c: Chunk[O]): StreamCore[F,O] = segment(Segment.Emit[F,O](c))
  def emit[F[_],O](w: O): StreamCore[F,O] = chunk(Chunk.singleton(w))
  def empty[F[_],O]: StreamCore[F,O] = chunk(Chunk.empty)
  def fail[F[_],O](err: Throwable): StreamCore[F,O] = segment(Segment.Fail[F,O](err))
  def attemptEval[F[_],O](f: F[O]): StreamCore[F,Attempt[O]] = new StreamCore[F,Attempt[O]] {
    type O0 = Attempt[O]
    def push[G[_],O2](u: NT[F,G], stack: Stack[G,Attempt[O],O2]) =
      Scope.attemptEval(NT.convert(f)(u)) map { o => stack push Segment.Emit(Chunk.singleton(o)) }
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

    final def translate[G[_]](u: NT[F,G]): Segment[G,O1] = u.same match {
      case Left(sub) => Sub1.substSegment(this)(sub)
      case Right(uu) => this match {
        case Append(s) => Append(s translate u)
        case Handler(h) => Handler(NT.convert(h)(u))
        case Emit(c) => this.asInstanceOf[Segment[G,O1]]
        case Fail(e) => this.asInstanceOf[Segment[G,O1]]
      }
    }

    final def mapChunks[O2](f: Chunk[O1] => Chunk[O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.mapChunks(f))
      case Handler(h) => Handler(t => h(t).mapChunks(f))
      case Emit(c) => Emit(f(c))
      case Fail(e) => this.asInstanceOf[Segment[F,O2]]
    }

    final def interpretBind[O2](f: O1 => StreamCore[F, O2]): Segment[F, O2] = this match {
      case Append(s) => Append(s.flatMap(f))
      case Handler(h) => Handler(t => h(t).flatMap(f))
      case Emit(c) => if (c.isEmpty) this.asInstanceOf[Segment[F,O2]] else Append(c.toVector.map(o => attemptStream(f(o))).reduceRight((s, acc) => StreamCore.append(s, acc)))
      case Fail(e) => this.asInstanceOf[Segment[F,O2]]
    }
  }
  object Segment {
    final case class Fail[F[_],O1](err: Throwable) extends Segment[F,O1]
    final case class Emit[F[_],O1](c: Chunk[O1]) extends Segment[F,O1]
    final case class Handler[F[_],O1](h: Throwable => StreamCore[F,O1]) extends Segment[F,O1] {
      override def toString = "Handler"
    }
    final case class Append[F[_],O1](s: StreamCore[F,O1]) extends Segment[F,O1]
  }

  sealed trait Stack[F[_],O1,O2] {

    def render: List[String]

    final def pushHandler(f: Throwable => StreamCore[F,O1]) = push(Segment.Handler(f))
    final def pushEmit(s: Chunk[O1]) = push(Segment.Emit(s))
    final def pushFail(e: Throwable) = push(Segment.Fail(e))
    final def pushAppend(s: StreamCore[F,O1]) = push(Segment.Append(s))
    final def pushBind[O0](f: O0 => StreamCore[F,O1]): Stack[F,O0,O2] =
      Stack.Bind(Catenable.empty, f, this)
    final def pushMap[O0](f: Chunk[O0] => Chunk[O1]): Stack[F,O0,O2] =
      Stack.Map(Catenable.empty, f, this)
    final def push(s: Segment[F,O1]): Stack[F,O1,O2] = s match {
      case Segment.Emit(c) if c.isEmpty => this
      case _ => pushNonEmptySegment(s)
    }
    protected def pushNonEmptySegment(s: Segment[F,O1]): Stack[F,O1,O2]

    final def pushSegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
      if (s.isEmpty) this
      else pushNonEmptySegments(s)
    protected def pushNonEmptySegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2]

    final def step: Scope[F,StepResult[F,O2]] =
      Scope.interrupted.flatMap { interrupted =>
        if (interrupted) Scope.pure(StepResult.Failed(Interrupted))
        else _step
      }

    protected def _step: Scope[F,StepResult[F,O2]]
  }

  object Stack {
    private final case class Segments[F[_],O](segments: Catenable[Segment[F,O]]) extends Stack[F,O,O] {

      def render = {
        val segmentsList = segments.toList
        List(s"Segments (${segmentsList.size})\n"+segmentsList.zipWithIndex.map { case (s, idx) => s"    s$idx: $s" }.mkString("\n"))
      }
      def pushNonEmptySegment(s: Segment[F,O]): Stack[F,O,O] =
        Segments(s +: segments)

      def pushNonEmptySegments(s: Catenable[Segment[F,O]]): Stack[F,O,O] =
        Segments(s ++ segments)

      def _step: Scope[F,StepResult[F,O]] = {
        segments.uncons match {
          case None => Scope.pure(StepResult.Done)
          case Some((hd, segments)) => hd match {
            case Segment.Fail(err) => Stack.fail[F,O](segments)(err) match {
              case Left(err) => Scope.pure(StepResult.Failed(err))
              case Right((s, segments)) => Stack.segments(segments).pushAppend(s).step
            }
            case Segment.Emit(chunk) =>
              if (chunk.isEmpty) Stack.segments(segments).step
              else Scope.pure(StepResult.Emits(NonEmptyChunk.fromChunkUnsafe(chunk), StreamCore.segments(segments)))
            case Segment.Handler(h) => Stack.segments(segments).step
            case Segment.Append(s) => s.push(NT.Id(), Stack.segments(segments)).flatMap(_.step)
          }
        }
      }
    }

    private sealed trait Map[F[_],O1,O2] extends Stack[F,O1,O2] {
      val segments: Catenable[Segment[F,O1]]
      type X
      val f: Chunk[O1] => Chunk[X]
      val stack: Stack[F,X,O2]

      def render = "Map" :: stack.render

      def pushNonEmptySegment(s: Segment[F,O1]): Stack[F,O1,O2] =
        Map(s +: segments, f, stack)

      def pushNonEmptySegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
        Map(s ++ segments, f, stack)

      def _step: Scope[F,StepResult[F,O2]] =
        segments.uncons match {
          case None => stack.step
          case Some((hd, segments)) => hd match {
            case Segment.Emit(chunk) =>
              val segs2 = segments.map(_.mapChunks(f))
              val stack2 = stack.pushSegments(segs2)
              (try { stack2.pushEmit(f(chunk)) } catch { case NonFatal(e) => stack2.pushFail(e) }).step
            case Segment.Append(s) =>
              s.push(NT.Id(), stack.pushMap(f).pushSegments(segments)).flatMap(_.step)
            case Segment.Fail(err) => Stack.fail(segments)(err) match {
              case Left(err) => stack.pushFail(err).step
              case Right((hd, segments)) => stack.pushMap(f).pushSegments(segments).pushAppend(hd).step
            }
            case Segment.Handler(_) => stack.pushMap(f).pushSegments(segments).step
          }
        }
    }
    private object Map {
      def apply[F[_],O1,O2,X0](segments0: Catenable[Segment[F,O1]], f0: Chunk[O1] => Chunk[X0], stack0: Stack[F,X0,O2]): Map[F,O1,O2] =
        new Map[F,O1,O2] {
          val segments = segments0
          type X = X0
          val f = f0
          val stack = stack0
        }
    }

    private sealed trait Bind[F[_],O1,O2] extends Stack[F,O1,O2] {
      val segments: Catenable[Segment[F,O1]]
      type X
      val f: O1 => StreamCore[F,X]
      val stack: Stack[F,X,O2]

      def render = "Bind" :: stack.render

      def pushNonEmptySegment(s: Segment[F,O1]): Stack[F,O1,O2] =
        Bind(s +: segments, f, stack)

      def pushNonEmptySegments(s: Catenable[Segment[F,O1]]): Stack[F,O1,O2] =
        Bind(s ++ segments, f, stack)

      def _step: Scope[F,StepResult[F,O2]] =
        segments.uncons match {
          case None => stack.step
          case Some((hd, segments)) => hd match {
            case Segment.Emit(chunk) =>
              chunk.uncons match {
                case None => stack.pushBind(f).pushSegments(segments).step
                case Some((hd,tl)) =>
                  val segs2 =
                    (if (tl.isEmpty) segments else segments.cons(Segment.Emit(tl))).map(_.interpretBind(f))
                  val stack2 = stack.pushSegments(segs2)
                  (try stack2.pushAppend(f(hd)) catch { case NonFatal(t) => stack2.pushFail(t) }).step
              }
            case Segment.Append(s) =>
              s.push(NT.Id(), stack.pushBind(f).pushSegments(segments)).flatMap(_.step)
            case Segment.Fail(err) => Stack.fail(segments)(err) match {
              case Left(err) => stack.pushFail(err).step
              case Right((hd, segments)) => stack.pushBind(f).pushSegments(segments).pushAppend(hd).step
            }
            case Segment.Handler(_) => stack.pushBind(f).pushSegments(segments).step
          }
        }
    }
    private object Bind {
      def apply[F[_],O1,O2,X0](segments0: Catenable[Segment[F,O1]], f0: O1 => StreamCore[F,X0], stack0: Stack[F,X0,O2]): Bind[F,O1,O2] =
        new Bind[F,O1,O2] {
          val segments = segments0
          type X = X0
          val f = f0
          val stack = stack0
        }
    }

    def empty[F[_],O1]: Stack[F,O1,O1] = segments(Catenable.empty)

    def segments[F[_],O1](s: Catenable[Segment[F,O1]]): Stack[F,O1,O1] = Segments(s)

    @annotation.tailrec
    def fail[F[_],O1](s: Catenable[Segment[F,O1]])(err: Throwable)
    : Attempt[(StreamCore[F,O1], Catenable[Segment[F,O1]])]
    = s.uncons match {
      case None => Left(err)
      case Some((Segment.Handler(f),tl)) => Right(attemptStream(f(err)) -> tl)
      case Some((_, tl)) => fail(tl)(err)
    }
  }

  private
  def runCleanup[F[_]](l: Resources[Token,Free[F,Attempt[Unit]]]): Free[F,Attempt[Unit]] =
    l.closeAll(scala.collection.immutable.Stream()) match {
      case Right(l) => runCleanup(l.map(_._2))
      case Left(_) => sys.error("internal FS2 error: cannot run cleanup actions while resources are being acquired: "+l)
    }

  private[fs2]
  def runCleanup[F[_]](cleanups: Iterable[Free[F,Attempt[Unit]]]): Free[F,Attempt[Unit]] = {
    // note - run cleanup actions in FIFO order, but avoid left-nesting flatMaps
    // all actions are run but only first error is reported
    cleanups.toList.reverse.foldLeft[Free[F,Attempt[Unit]]](Free.pure(Right(())))(
      (tl,hd) => hd flatMap { _.fold(e => tl flatMap { _ => Free.pure(Left(e)) }, _ => tl) }
    )
  }
}
