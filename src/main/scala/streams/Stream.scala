package streams

import collection.immutable.LongMap
import streams.util.UF1._

/**
 * A stream producing output of type `W`, which may evaluate `F`
 * effects. If `F` is `Nothing`, the stream is pure.
 */
trait Stream[+F[_],+W] {
  import Stream.Stack

  def runFold[O](g: (O,W) => O)(z: O): Free[F, O] =
    _runFold0(0, LongMap.empty, Stream.emptyStack[F,W])(g, z)

  def flatMap[F2[_],W2](f: W => Stream[F2,W2])(implicit S: Sub1[F,F2]): Stream[F2,W2] =
    Stream.flatMap(Sub1.substStream(this))(f)

  protected final def _runFold0[F2[_],O,W2>:W,W3](
    nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O] =
    Free.pure(()) flatMap { _ => // trampoline after every step, catch exceptions
      try _runFold1(nextID, tracked, k)(g, z)
      catch { case t: Throwable => Stream.fail(t)._runFold1(nextID, tracked, k)(g,z) }
    }

  /**
   * The implementation of `runFold`. Not public. Note on parameters:
   *
   *   - `nextID` is used to generate fresh IDs
   *   - `tracked` is a map of the current in-scope finalizers,
   *     guaranteed to be run at most once before this `Stream` terminates
   *   - `k` is the stack of binds remaining. When empty, we obtain
   *     proof that `W2 == W3`, and can fold `g` over any emits.
   */
  protected def _runFold1[F2[_],O,W2>:W,W3](
    nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O]

  private[streams]
  final def step: Pull[F,Nothing,Step[Chunk[W], Stream.Handle[F,W]]] =
    _step0(List())

  private[streams]
  final def _step0[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2]):
    Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]
    = Pull.suspend { _step1(rights) } // trampoline and catch errors

  /**
   * The implementation of `step`. Not public. `rights` is the stack of
   * streams to the right of our current location. These will be appended
   * to the returned `Handle`.
   */
  protected def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2]):
    Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]

  private[streams]
  final def stepAsync[F2[_],W2>:W](implicit S: Sub1[F,F2], F2: Async[F2]):
    Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
    = _stepAsync0(List())

  private[streams]
  final def _stepAsync0[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2], F2: Async[F2]):
    Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
    = Pull.suspend { _stepAsync1(rights) } // trampoline and catch errors

  /**
   * The implementation of `stepAsync`. Not public. `rights` is the stack of
   * streams to the right of our current location. These will be appended
   * to the returned `Handle`.
   */
  protected def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2], F2: Async[F2]):
    Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]

  def translate[G[_]](uf1: F ~> G): Stream[G,W]
}

object Stream extends Streams[Stream] with StreamDerived {

  def chunk[W](c: Chunk[W]) = new Stream[Nothing,W] { self =>
    type F[x] = Nothing
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2,O]
      =
      if (c.isEmpty) k (
        (_,_) => runCleanup(tracked) map (_ => z),
        new k.H[Free[F2,O]] { def f[x] = (kh,k) =>
          if (kh.appends.isEmpty) // NB: kh.appends match triggers scalac bug
            empty._runFold0(nextID, tracked, k)(g, z)
          else {
            val k2 = k.push[W2](kh.copy(appends = kh.appends.tail))
            kh.appends.head()._runFold0(nextID, tracked, k2)(g,z)
          }
        }
      )
      else k (
        (to,_) => empty._runFold0(nextID, tracked, k)(g, c.foldLeft(z)((z,w) => g(z,to(w)))),
        new k.H[Free[F2,O]] { def f[x] = (kh,k) => {
          val p = c.foldRight(empty[x]: Stream[F2,x])((w,px) => kh.bind(w) ++ px)
          p._runFold0(nextID, tracked, k)(g, z)
        }}
      )

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[Nothing,F2])
      : Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]
      = Pull.pure(Step(c, new Handle(concatRight(rights))))

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[Nothing,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      = _step1(rights).map(step => F2.pure(Pull.pure(step)))

    def translate[G[_]](uf1: Nothing ~> G): Stream[G,W] = self
  }

  def fail(err: Throwable) = new Stream[Nothing,Nothing] { self =>
    type W = Nothing
    def _runFold1[F2[_],O,W2>:Nothing,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2,O]
      =
      k (
        (_,_) => runCleanup(tracked) flatMap { _ => Free.fail(err) },
        new k.H[Free[F2,O]] { def f[x] = (kh,k) => {
          if (kh.handlers.isEmpty)
            self._runFold0(nextID, tracked, k)(g, z)
          else {
            val kh2 = kh.copy(handlers = kh.handlers.tail)
            kh.handlers.head(err)._runFold0(nextID, tracked, kh2 +: k)(g,z)
          }
        }}
      )

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[Nothing,F2])
      : Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]
      = Pull.fail(err)

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[Nothing,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      = _step1(rights).map(step => F2.pure(Pull.fail(err)))

    def translate[G[_]](uf1: Nothing ~> G): Stream[G,W] = self
  }

  def eval[F[_],W](f: F[W]): Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, O]
      =
      Free.attemptEval(S(f)) flatMap {
        case Left(e) => fail(e)._runFold0(nextID, tracked, k)(g, z)
        case Right(a) => emit(a)._runFold0(nextID, tracked, k)(g, z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      = Pull.eval(S(f)) map { w => Step(Chunk.singleton(w), new Handle(concatRight(rights))) }

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      =
      Pull.eval {
        F2.bind(F2.ref[W]) { ref =>
        F2.map(F2.set(ref)(S(f))) { _ =>
        F2.map(F2.get(ref)) { w =>
          Pull.pure(Step(Chunk.singleton(w: W2), new Handle(concatRight(rights))))
          : Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]
        }}}
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] = suspend { eval(uf1(f)) }
  }

  def translate[F[_],G[_],W](s: Stream[F,W])(u: F ~> G) =
    s.translate(u)

  def flatMap[F[_],W0,W](s: Stream[F,W0])(f: W0 => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      s._runFold0[F2,O,W0,W3](nextID, tracked, k.push(Frame(Sub1.substStreamF(f))))(g,z)

    override def flatMap[F2[_],W2](g: W => Stream[F2,W2])(implicit S: Sub1[F,F2])
      : Stream[F2,W2]
      =
      Stream.flatMap(Sub1.substStream(s)) { w0 =>
        suspend { Sub1.substStreamF(f).apply(w0) flatMap g }
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      = {
        val f2: W0 => Stream[F2,W] = Sub1.substStreamF(f)
        Sub1.substStream(s).step flatMap { case Step(hd, tl) => hd.uncons match {
          case None => (tl.stream flatMap f2)._step0(rights)
          case Some((ch,ct)) =>
            f2(ch)._step0(chunk(ct).flatMap(f2) ::
                          tl.stream.flatMap(f2) ::
                          rights)
        }}
      }

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      = {
        val f2: W0 => Stream[F2,W] = Sub1.substStreamF(f)
        Sub1.substStream(s).stepAsync map { future => F2.map(future) { pull =>
          pull.flatMap { case Step(hd, tl) => hd.uncons match {
            case None => (tl.stream flatMap f2)._step0(rights)
            case Some((ch,ct)) =>
              f2(ch)._step0(chunk(ct).flatMap(f2) ::
                            tl.stream.flatMap(f2) ::
                            rights)
          }}
        }}
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { s.translate(uf1) flatMap { w0 => f(w0).translate(uf1) } }
  }

  def append[F[_],W](s: Stream[F,W], s2: => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      s._runFold0[F2,O,W2,W3](nextID, tracked, push(k, Sub1.substStream(s2)))(g,z)

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      =
      Pull.or ( Sub1.substStream(s)._step0(Sub1.substStream(suspend(s2)) :: rights)
              , Sub1.substStream(s2)._step0(rights))

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      =
      Sub1.substStream(s)._stepAsync0(Sub1.substStream(suspend(s2)) :: rights) map { future =>
        F2.map(future) { pull =>
          Pull.or (pull,
                   Sub1.substStream(s2)._stepAsync0(rights)
                       .flatMap(Pull.eval).flatMap(identity))
        }
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { s.translate(uf1) ++ s2.translate(uf1) }
  }

  private[streams] def scope[F[_],W](inner: Long => Stream[F,W]): Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      inner(nextID)._runFold0(nextID+1, tracked, k)(g, z)

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      =
      Pull.scope { id => Pull.suspend { inner(id)._step0(rights) } }

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      = Pull.scope { id => Pull.suspend { inner(id)._stepAsync0(rights) }}

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      scope { id => suspend { inner(id).translate(uf1) } }
  }

  private[streams] def acquire[F[_],W](id: Long, r: F[W], cleanup: W => F[Unit]):
  Stream[F,W] = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      =
      Free.eval(S(r)) flatMap { r =>
        try
          emit(r)._runFold0[F2,O,W2,W3](nextID, tracked updated (id, S(cleanup(r))), k)(g,z)
        catch { case t: Throwable =>
          Free.fail(
            new RuntimeException("producing resource cleanup action failed", t))
        }
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      =
      Sub1.substPull(Pull.acquire(id, r, cleanup)) flatMap { r =>
        Pull.track(id) map { _ =>
          Step(Chunk.singleton(r), new Handle(concatRight(rights)))
      }}

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      =
      Pull.eval {
        F2.bind(F2.ref[W]) { ref =>
        F2.map(F2.set(ref)(S(r))) { _ =>
          F2.pure { Stream.acquire[F2,W](
            id,
            F2.get(ref),
            Sub1.substKleisli(cleanup))._step0(rights)
            : Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]
          }
        }}
      }

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      suspend { acquire(id, uf1(r), cleanup andThen (uf1(_))) }
  }

  private[streams] def release(id: Long): Stream[Nothing,Nothing] = new Stream[Nothing,Nothing] {
    type W = Nothing
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2,O]
      =
      tracked.get(id).map(Free.eval).getOrElse(Free.pure(())) flatMap { _ =>
        empty._runFold0(nextID, tracked - id, k)(g, z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[Nothing,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      =
      Pull.release(id) flatMap { _ => concatRight(rights).step }

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[Nothing,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      = Pull.release(id) flatMap { _ => concatRight(rights).stepAsync }

    def translate[G[_]](uf1: Nothing ~> G): Stream[G,Nothing] = this
  }

  def onError[F[_],W](s: Stream[F,W])(handle: Throwable => Stream[F,W]) = new Stream[F,W] {
    def _runFold1[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2,O]
      = {
        val handle2: Throwable => Stream[F2,W] = handle andThen (Sub1.substStream(_))
        s._runFold0(nextID, tracked, push(k, handle2))(g, z)
      }

    def _step1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(implicit S: Sub1[F,F2])
      : Pull[F2,Nothing,Step[Chunk[W2],Stream.Handle[F2,W2]]]
      =
      Pull.onError(Sub1.substStream(s)._step0(rights).map {
        // keep the error handler in scope as we traverse the stream `s`
        case Step(hd,tl) =>
             Step(hd, new Handle(onError(tl.stream)(Sub1.substStreamF(handle))))
      }) { err =>
        Pull.suspend { Sub1.substStreamF(handle).apply(err)._step0(rights) }
      }

    def _stepAsync1[F2[_],W2>:W](rights: List[Stream[F2,W2]])(
      implicit S: Sub1[F,F2], F2: Async[F2])
      : Pull[F2,Nothing,F2[Pull[F2,Nothing,Step[Chunk[W2], Stream.Handle[F2,W2]]]]]
      =
      Pull.onError(Sub1.substStream(s)._stepAsync0(rights).map { future => F2.map(future) {
        pull => pull.map { case Step(hd,tl) =>
          Step(hd, new Handle(onError(tl.stream)(Sub1.substStreamF(handle))))
        }
      }}) { err => Pull.suspend { Sub1.substStreamF(handle).apply(err)._stepAsync0(rights) }}

    def translate[G[_]](uf1: F ~> G): Stream[G,W] =
      onError(s.translate(uf1)) { err => suspend { handle(err).translate(uf1) }}
  }

  def bracket[F[_],R,W](r: F[R])(use: R => Stream[F,W], cleanup: R => F[Unit]) =
    scope { id => onComplete(acquire(id, r, cleanup) flatMap use, release(id)) }

  def push[F[_],W](h: Handle[F,W])(c: Chunk[W]) = new Handle(chunk(c) ++ h.stream)
  def open[F[_],W](s: Stream[F,W]) = Pull.pure(new Handle(s))
  def await[F[_],W](h: Handle[F,W]) = h.stream.step
  def awaitAsync[F[_]:Async,W](h: Handle[F,W]) = h.stream.stepAsync

  type Pull[+F[_],+W,+R] = streams.Pull[F,W,R]
  val Pull = streams.Pull

  def runFold[F[_],W,O](s: Stream[F,W], z: O)(g: (O,W) => O) =
    s.runFold(g)(z)

  class Handle[+F[_],+W](private[streams] val stream: Stream[F,W])

  def push[F[_],W,W2](c: Stack[F,W,W2], p: => Stream[F,W]): Stack[F,W,W2] =
    c.push { Frame(emit(_), List(() => p)) }

  def push[F[_],W,W2](c: Stack[F,W,W2], h: Throwable => Stream[F,W]): Stack[F,W,W2] =
    c.push { Frame(emit(_), List(), List(h)) }

  private def runCleanup[F[_]](l: LongMap[F[Unit]]): Free[F,Unit] =
    l.values.foldLeft[Free[F,Unit]](Free.pure(()))((tl,hd) =>
      Free.eval(hd) flatMap { _ => tl } )

  private def concatRight[F[_],W](s: List[Stream[F,W]]): Stream[F,W] =
    s.reverse.foldLeft(empty: Stream[F,W])((tl,hd) => append(hd,tl))

  case class Frame[F[_],W1,W2](
    bind: W1 => Stream[F,W2],
    appends: List[() => Stream[F,W1]] = List(),
    handlers: List[Throwable => Stream[F,W1]] = List())

  private trait T[F[_]] { type f[a,b] = Frame[F,a,b] }
  type Stack[F[_],A,B] = streams.Chain[T[F]#f, A, B]

  private def emptyStack[F[_],A]: Stack[F,A,A] = streams.Chain.empty[T[F]#f, A]
}

