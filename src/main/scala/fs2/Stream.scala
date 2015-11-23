package fs2

import collection.immutable.LongMap
import fs2.internal.{ConcurrentLinkedMap,LinkedSet,Trampoline}
import fs2.util._
import fs2.Async.Future

/**
 * A stream producing output of type `W`, which may evaluate `F`
 * effects. If `F` is `Nothing`, the stream is pure.
 */
trait Stream[+F[_],+W] extends StreamOps[F,W] {
  import Stream.{Handle,Stack,Token}

  def isEmpty: Boolean = false

  def runFold[O](g: (O,W) => O)(z: O): Free[F, O] =
    _runFold0(true, ConcurrentLinkedMap.empty[Token,F[Unit]], Stream.Stack.empty[F,W])(g, z)

  protected final def _runFold0[F2[_],O,W2>:W,W3](
    doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O] =
    Free.pure(()) flatMap { _ => // trampoline after every step, catch exceptions
      try _runFold1(doCleanup, tracked, k)(g, z)
      catch { case t: Throwable => Stream.fail(t)._runFold1(doCleanup, tracked, k)(g,z) }
    }

  /**
   * The implementation of `runFold`. Not public. Note on parameters:
   *
   *   - `tracked` is a map of the current in-scope finalizers,
   *     guaranteed to be run at most once before this `Stream` terminates
   *   - `k` is the stack of binds remaining. When empty, we obtain
   *     proof that `W2 == W3`, and can fold `g` over any emits.
   */
  protected def _runFold1[F2[_],O,W2>:W,W3](
    doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O]

  private[fs2]
  final def step: Pull[F,Nothing,Step[Chunk[W], Handle[F,W]]] =
    _step0(List())

  private[fs2]
  final def _step0[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2]):
    Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]
    = Pull.suspend { _step1(rights) } // trampoline and catch errors

  /**
   * The implementation of `step`. Not public. `rights` is the stack of
   * streams to the right of our current location. These will be appended
   * to the returned `Handle`.
   */
  protected def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2]):
    Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]

  private[fs2]
  final def stepAsync[F2[_],W2>:W](implicit S: Sub1[F,F2], F2: Async[F2]):
    Pull[F2,Nothing,Future[F2, Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]]]
    = Pull.eval(F2.ref[Unit]).flatMap { gate =>
      type Out = Step[Chunk[W2],Handle[F2,W2]]
      type OutE = Either[Throwable,Out]
      val s: Stream[F2,Out] =
        _step1[F2,W2](List()).flatMap { step => Pull.output1(step) }.
        _run0(doCleanup = false, LinkedSet.empty, Pull.Stack.empty[F2,Out,Unit])
      val s2: Stream[F2,Either[Throwable,Out]] =
        Stream.onComplete(s, Stream.eval_(F2.set(gate)(F2.pure(())))).map(Right(_))
              .onError(err => Stream.emit(Left(err)))
      val resources = ConcurrentLinkedMap.empty[Token,F2[Unit]]
      val f = (o: Option[OutE], o2: OutE) => Some(o2)
      val free: Free[F2,Option[OutE]] = s2._runFold0(doCleanup = false, resources, Stack.empty[F2,OutE])(f, None)
      val runStep: F2[Option[OutE]] = free.run
      val rootToken = new Token()
      val rootCleanup: F2[Unit] = F2.bind(F2.get(gate)) { _ => Stream.runCleanup(resources).run }
      Pull.track(rootToken) >>
      Pull.acquire(rootToken, F2.pure(()), (u:Unit) => rootCleanup) >>
      Pull.eval(F2.ref[Option[OutE]]).flatMap { out =>
        Pull.eval(F2.set(out)(runStep)).map { _ =>
          F2.read(out).map {
            case None => Pull.done: Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
            case Some(Left(err)) => Pull.fail(err)
            case Some(Right(a)) => Pull.pure(a)
          }.appendOnForce { Pull.suspend {
            // if resources is empty here, rootToken doesn't track anything, free it
            if (resources.isEmpty) Pull.release(rootToken)
            else Pull.pure(())
          }}
        }
      }
    }

  def translate[G[_]](uf1: F ~> G): Stream[G,W]
}

object Stream extends Streams[Stream] with StreamDerived {

  private[fs2] class Token()
  private[fs2] def token: Token = new Token()

  def chunk[F[_],W](c: Chunk[W]) = new Stream[F,W] { self =>
    override def isEmpty = c.isEmpty

    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      k (
        (segments,eq) => segments match {
          case List() =>
            val r = Free.attemptPure { c.foldLeft(z)((z,w) => g(z,eq(w))) }
            if (doCleanup) runCleanup(tracked) flatMap { _ => r }
            else r
          case _ =>
            val z2 = c.foldLeft(z)((z,w) => g(z,eq(w)))
            val (hd, tl) = Stack.succeed(segments)
            val g2 = Eq.subst[({ type f[x] = (O,x) => O })#f, W3, W2](g)(eq.flip)
            hd._runFold0(doCleanup, tracked, Stack.segments(tl))(g2, z2)
        },
        new k.H[Free[F2,O]] { def f[x] = (segments, bindf, tl) => {
          if (c.isEmpty) { // empty.flatMap(f) == empty
            if (segments.isEmpty) empty[F2,x]._runFold0(doCleanup, tracked, tl)(g,z)
            else {
              val (hd, tls) = Stack.succeed(segments)
              hd._runFold0(doCleanup, tracked, tl.pushBind(bindf).pushSegments(tls))(g, z)
            }
          }
          else {
            val c2: Stream[F2,x] = bindf.fold(
              mapf => chunk[F2,x](c map mapf),
              bindf => c.foldRight(None: Option[Stream[F2,x]])(
                (w,acc) => acc match {
                  case None => Some(bindf(w))
                  case Some(acc) => Some(Stream.append(bindf(w), acc))
                }
              ).getOrElse(empty[F2,x])
            )
            val bsegments = bindf.fold(
              mapf => Stack.mapSegments(segments)(mapf),
              bindf => Stack.bindSegments(segments)(bindf))
            c2._runFold0(doCleanup, tracked, tl.pushSegments(bsegments))(g, z)
          }
        }}
      )

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]
      = if (c.isEmpty) { // NB: for some reason, scala can't handle matching on `rights`
          if (rights.nonEmpty) rights.head._step0(rights.tail)
          else Pull.done
        }
        else
          Pull.pure(Step(c, new Handle(List(), concatRight(rights))))

    def translate[G[_]](uf1: F ~> G): Stream[G,W] = chunk[G,W](c)

    override def toString = "Stream(" + c.iterator.mkString(", ") + ")"
  }

  def fail[F[_]](err: Throwable): Stream[F,Nothing] = new Stream[F,Nothing] { self =>
    type W = Nothing
    def _runFold1[F2[_],O,W2>:Nothing,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      k (
        (segments,eq) => segments match {
          case List() => empty[F2,W2]._runFold1(doCleanup, tracked, k)(g,z) flatMap { _ => Free.fail(err) }
          case _ =>
            val (hd, tl) = Stack.fail(segments)(err)
            val g2 = Eq.subst[({ type f[x] = (O,x) => O })#f, W3, W2](g)(eq.flip)
            hd._runFold0(doCleanup, tracked, Stack.segments(tl))(g2,z)
        },
        new k.H[Free[F2,O]] { def f[x] = (segments, bindf, tl) => segments match {
          case List() => fail(err)._runFold0(doCleanup, tracked, tl)(g, z)
          case _ =>
            val (hd, tls) = Stack.fail(segments)(err)
            hd._runFold0(doCleanup, tracked, tl.pushBind(bindf).pushSegments(tls))(g, z)
        }}
      )

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]
      = Pull.fail(err)

    def translate[G[_]](uf1: F ~> G): Stream[G,W] = self.asInstanceOf[Stream[G,W]]
  }

  def eval[F[_],W](f: F[W]): Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O]
      =
      Free.attemptEval(S(f)) flatMap {
        case Left(e) => fail(e)._runFold0(doCleanup, tracked, k)(g, z)
        case Right(a) => emit(a)._runFold0(doCleanup, tracked, k)(g, z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      = Pull.eval(S(f)) map { w => Step(Chunk.singleton(w), new Handle(List(), concatRight(rights))) }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] = suspend { eval(uf1(f)) }
  }

  def translate[F[_],G[_],W](s: Stream[F,W])(u: F ~> G) =
    s.translate(u)

  override def map[F[_],W0,W](s: Stream[F,W0])(f: W0 => W) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      Free.suspend {
        s._runFold0[F2,O,W0,W3](doCleanup, tracked, k.pushBind(Left(f)))(g,z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      = {
        val s2 = Sub1.substStream(s).step map { case Step(hd, tl) =>
          Step(hd map f, {
            val h = tl.map(f)
            new Handle(h.buffer, if (rights.nonEmpty) h.underlying ++ concatRight(rights) else h.underlying)
          })
        }
        if (rights.nonEmpty) s2 or rights.head._step0(rights.tail)
        else s2
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { s.translate(uf1) map f }
  }

  def flatMap[F[_],W0,W](s: Stream[F,W0])(f: W0 => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      Free.suspend {
        s._runFold0[F2,O,W0,W3](doCleanup, tracked, k.pushBind(Right(Sub1.substStreamF(f))))(g,z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      = {
        val f2: W0 => Stream[F2,W] = Sub1.substStreamF(f)
        val s2 = Sub1.substStream(s).step flatMap { case Step(hd, tl) => hd.uncons match {
          case None => (tl.stream flatMap f2)._step0(rights)
          case Some((ch,ct)) => // important optimization - skip adding `empty.flatMap(f)` to the stack
            val tls = tl.stream
            val rights1 = if (tls.isEmpty) rights else tls.flatMap(f2) :: rights
            val rights2 = if (ct.isEmpty) rights1 else chunk(ct).flatMap(f2) :: rights1
            f2(ch)._step0(rights2)
        }}
        if (rights.nonEmpty) s2 or rights.head._step0(rights.tail)
        else s2
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { s.translate(uf1) flatMap { w0 => f(w0).translate(uf1) } }
  }

  def append[F[_],W](s: Stream[F,W], s2: => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      s._runFold0[F2,O,W2,W3](doCleanup, tracked, k.pushAppend(() => Sub1.substStream(s2)))(g,z)

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      =
      Sub1.substStream(s)._step0(Sub1.substStream(suspend(s2)) :: rights)

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { s.translate(uf1) ++ s2.translate(uf1) }
  }

  private[fs2] def acquire[F[_],W](id: Token, r: F[W], cleanup: W => F[Unit]):
  Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      Free.eval(S(r)) flatMap { r =>
        try
          emit(r)._runFold0[F2,O,W2,W3](doCleanup, tracked updated (id, S(cleanup(r))), k)(g,z)
        catch { case t: Throwable =>
          fail(new RuntimeException("producing resource cleanup action failed", t))
          ._runFold0[F2,O,W2,W3](doCleanup, tracked, k)(g,z)
        }
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      =
      Sub1.substPull(Pull.acquire(id, r, cleanup)) flatMap { r =>
        Pull.track(id) map { _ =>
          Step(Chunk.singleton(r), new Handle(List(), concatRight(rights)))
      }}

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { acquire(id, uf1(r), cleanup andThen (uf1(_))) }
  }

  private[fs2] def release(id: Token): Stream[Nothing,Nothing] = new Stream[Nothing,Nothing] {
    type W = Nothing
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2,O]
      =
      tracked.get(id).map(eval).getOrElse(Stream.emit(())).flatMap { (u: Unit) =>
        empty[F2,W2]
      }._runFold0(doCleanup, tracked.removed(id), k)(g, z)

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[Nothing,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      =
      Pull.release(id) flatMap { _ => concatRight(rights).step }

    def translate[G[_]](uf1: Nothing ~> G): Stream[G,Nothing] = this
  }

  def onError[F[_],W](s: Stream[F,W])(handle: Throwable => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      = {
        val handle2: Throwable => Stream[F2,W] = handle andThen (Sub1.substStream(_))
        s._runFold0(doCleanup, tracked, k.pushHandler(handle2))(g, z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Handle[F2,W2]]]
      =
      Pull.onError(Sub1.substStream(s)._step0(rights).map {
        // keep the error handler in scope as we traverse the stream `s`
        case Step(hd,tl) =>
             Step(hd, new Handle(List(), Stream.onError(tl.stream)(Sub1.substStreamF(handle))))
      }) { err =>
        Pull.suspend { Sub1.substStreamF(handle).apply(err)._step0(rights) }
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      Stream.onError(s.translate(uf1)) { err => suspend { handle(err).translate(uf1) }}
  }

  def bracket[F[_],R,W](r: F[R])(use: R => Stream[F,W], cleanup: R => F[Unit]) = suspend {
    val id = token
    onComplete(acquire(id, r, cleanup) flatMap use, release(id))
  }

  /**
   * Produce a `Stream` nonstrictly, catching exceptions. `suspend { p }` behaves
   * the same as `emit(()).flatMap { _ => p }`.
   */
  def suspend[F[_],W](self: => Stream[F,W]): Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      doCleanup: Boolean, tracked: ConcurrentLinkedMap[Token,F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      = self._runFold0(doCleanup, tracked, k)(g, z)

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2], Handle[F2,W2]]]
      = self._step0(rights)

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { try self.translate(uf1) catch { case e: Throwable => fail(e) } }
  }


  def push[F[_],W](h: Handle[F,W])(c: Chunk[W]) =
    if (c.isEmpty) h
    else new Handle(c :: h.buffer, h.underlying)

  def open[F[_],W](s: Stream[F,W]) = Pull.pure(new Handle(List(), s))

  def await[F[_],W](h: Handle[F,W]) =
    h.buffer match {
      case List() => h.underlying.step
      case hb :: tb => Pull.pure(Step(hb, new Handle(tb, h.underlying)))
    }

  def awaitAsync[F[_],W](h: Handle[F,W])(implicit F: Async[F]) =
    h.buffer match {
      case List() => h.underlying.stepAsync
      case hb :: tb => Pull.pure(Future.pure(Pull.pure(Step(hb, new Handle(tb, h.underlying)))))
    }

  type Pull[+F[_],+W,+R] = fs2.Pull[F,W,R]
  val Pull = fs2.Pull

  def runFold[F[_],W,O](s: Stream[F,W], z: O)(g: (O,W) => O) =
    s.runFold(g)(z)

  class Handle[+F[_],+W](private[fs2] val buffer: List[Chunk[W]],
                         private[fs2] val underlying: Stream[F,W]) {
    private[fs2] def stream: Stream[F,W] = {
      def go(buffer: List[Chunk[W]]): Stream[F,W] = buffer match {
        case List() => underlying
        case c :: buffer => chunk(c) ++ go(buffer)
      }
      go(buffer)
    }
    def map[W2](f: W => W2) = new Handle(buffer.map(_ map f), underlying map f)
  }

  object Handle {
    def empty[F[_],W]: Handle[F,W] = new Handle(List(), Stream.empty)
  }

  private def runCleanup[F[_]](l: ConcurrentLinkedMap[Token,F[Unit]]): Free[F,Unit] =
    l.takeValues.foldLeft[Free[F,Unit]](Free.pure(()))((tl,hd) =>
      Free.eval(hd) flatMap { _ => tl } )

  private def concatRight[F[_],W](s: List[Stream[F,W]]): Stream[F,W] =
    if (s.isEmpty) empty
    else s.reverse.reduceLeft((tl,hd) => if (hd.isEmpty) tl else append(hd,tl))

  sealed trait Segment[F[_],W1]
  object Segment {
    case class Handler[F[_],W1](h: Throwable => Stream[F,W1]) extends Segment[F,W1]
    case class Append[F[_],W1](s: Trampoline[Stream[F,W1]]) extends Segment[F,W1]
  }

  trait Stack[F[_],W1,W2] { self =>
    def apply[R](
      unbound: (List[Segment[F,W1]], Eq[W1,W2]) => R,
      bound: H[R]
    ): R

    trait H[+R] { def f[x]: (List[Segment[F,W1]], Either[W1 => x, W1 => Stream[F,x]], Stack[F,x,W2]) => R }

    def pushBind[W0](f: Either[W0 => W1, W0 => Stream[F,W1]]): Stack[F,W0,W2] = new Stack[F,W0,W2] {
      def apply[R](unbound: (List[Segment[F,W0]], Eq[W0,W2]) => R, bound: H[R]): R
      = bound.f(List(), f, self)
    }

    def push(s: Segment[F,W1]): Stack[F,W1,W2] = self (
      (segments, eq) => Eq.subst[({type f[x] = Stack[F,W1,x] })#f, W1, W2](
                        Stack.segments(s :: segments))(eq),
      new self.H[Stack[F,W1,W2]] { def f[x] = (segments,bindf,tl) =>
        tl.pushBind(bindf).pushSegments(s :: segments)
      }
    )

    def pushHandler(f: Throwable => Stream[F,W1]) = push(Segment.Handler(f))
    def pushAppend(s: () => Stream[F,W1]) = push(Segment.Append(Trampoline.delay(s())))

    def pushSegments(s: List[Segment[F,W1]]): Stack[F,W1,W2] =
      if (s.isEmpty) self
      else new Stack[F,W1,W2] {
        def apply[R](unbound: (List[Segment[F,W1]], Eq[W1,W2]) => R, bound: H[R]): R
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
    def empty[F[_],W1]: Stack[F,W1,W1] = segments(List())

    def segments[F[_],W1](s: List[Segment[F,W1]]): Stack[F,W1,W1] = new Stack[F,W1,W1] {
      def apply[R](unbound: (List[Segment[F,W1]], Eq[W1,W1]) => R, bound: H[R]): R
      = unbound(s, Eq.refl)
    }

    @annotation.tailrec
    def fail[F[_],W1](s: List[Segment[F,W1]])(err: Throwable)
    : (Stream[F,W1], List[Segment[F,W1]])
    = s match {
      case List() => (Stream.fail(err), List())
      case Segment.Handler(f) :: tl => (f(err), tl)
      case _ :: tl => fail(tl)(err)
    }

    @annotation.tailrec
    def succeed[F[_],W1](s: List[Segment[F,W1]])
    : (Stream[F,W1], List[Segment[F,W1]])
    = s match {
      case List() => (Stream.empty, List())
      case Segment.Append(s) :: tl => (s.run, tl)
      case _ :: tl => succeed(tl)
    }

    def bindSegments[F[_],W1,W2](s: List[Segment[F,W1]])(f: W1 => Stream[F,W2])
    : List[Segment[F,W2]]
    = s map {
      case Segment.Append(s) => Segment.Append(s map { s => flatMap(s)(f) })
      case Segment.Handler(h) => Segment.Handler(h andThen (s => flatMap(s)(f)))
    }

    def mapSegments[F[_],W1,W2](s: List[Segment[F,W1]])(f: W1 => W2): List[Segment[F,W2]]
    = s map {
      case Segment.Append(s) => Segment.Append(s map { s => map(s)(f) })
      case Segment.Handler(h) => Segment.Handler(h andThen (s => map(s)(f)))
    }
  }
}

