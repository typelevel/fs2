package streams

case class NF[F[_],+A](cleanup: Finalizers[F], frame: NF.Frame[F,A])

object NF extends Stream[NF] {

  sealed trait Frame[F[_],+A]
  case class Emits[F[_],A](c: Chunk[A]) extends Frame[F,A]
  case class Cons[F[_],A](hd: Chunk[A], a2: () => NF[F,A]) extends Frame[F,A]
  case class Fail[F[_],A](err: Throwable) extends Frame[F,A]
  case class Await[F[_],A](f: Free[F,NF[F,A]]) extends Frame[F,A]
  case class Acquire[F[_],A](f: Free[F,(F[Unit], NF[F,A])]) extends Frame[F,A]

  def emits[F[_],A](c: Chunk[A]): NF[F,A] = NF(Finalizers.empty, Emits(c))
  def fail[F[_],A](e: Throwable): NF[F,A] = NF(Finalizers.empty, Fail(e))
  def free[F[_],A](f: Free[F,NF[F,A]]): NF[F,A] = NF(Finalizers.empty, Await(f))

  def append[F[_],A](a1: NF[F,A], a2: => NF[F,A]): NF[F,A] = NF(a1.cleanup, a1.frame match {
    case Emits(c) => Cons(c, () => a2)
    case Cons(h,t) => Cons(h, () => append(t(),a2))
    case Fail(e) => Fail(e)
    case Await(f) => Await(f map (a1 => append(a1, a2)))
    case Acquire(r) => Acquire(r map { case (release, a1) => (release, append(a1,a2)) })
  })

  def flatMap[F[_],A,B](a: NF[F,A])(f: A => NF[F,B]): NF[F,B] = {
    def go(a: NF[F,A])(f: A => NF[F,B]): NF[F,B] = a.frame match {
      case Fail(e) => fail(e)
      case Emits(c) => c.uncons match {
        case None => emits(Chunk.empty)
        case Some((hd,tl)) => append(f(hd), go(emits(tl))(f))
      }
      case Cons(h, t) => append(go(emits(h))(f), go(t())(f))
      case Await(g) => NF(a.cleanup, Await(g map (a => go(a)(f))))
      case Acquire(r) => NF(a.cleanup, Acquire(r map { case (release, a) => (release, go(a)(f)) }))
    }
    scope(a.cleanup) { go(a)(f) }
  }

  def scope[F[_],A](finalizers: Finalizers[F])(a: NF[F,A]): NF[F,A] =
    if (finalizers.isEmpty) a
    else NF(a.cleanup append finalizers, a.frame match {
      case Cons(h, t) => Cons(h, () => scope(finalizers)(t()))
      case Await(g) => Await(g map (scope(finalizers)))
      case Acquire(g) => Acquire(g map { case (release, a) => (release, scope(finalizers)(a)) })
      case _ => a.frame
    })

  def bracket[F[_],R,A](acquire: F[R])(use: R => NF[F,A], release: R => F[Unit])(
    implicit F: Affine[F]): NF[F,A] =
    NF(Finalizers.empty,
       Acquire(Free.Eval(acquire) flatMap { r =>
         Free.Eval { F.map(F.affine(release(r))) { cleanup =>
           // we are assured this finalizer will be run promptly when `use(r)`
           // completes, or by `runFold`, which detects when finalizers pass
           // out of scope
           (cleanup, scope(Finalizers.single(cleanup))(append(use(r), eval_(cleanup))))
         }}
       })
    )

  def onError[F[_],A](a: NF[F,A])(handle: Throwable => NF[F,A]): NF[F,A] = a.frame match {
    // keeps `a.cleanup` in scope for duration of handler
    case Fail(e) => scope(a.cleanup)(handle(e))
    case Emits(_) => a
    case Await(g) => NF(a.cleanup, Await(g map (a => onError(a)(handle))))
    case Acquire(g) => NF(a.cleanup, Acquire(g map { case (f,a) => (f, onError(a)(handle)) }))
    case Cons(h, t) => NF(a.cleanup, Cons(h, () => onError(t())(handle)))
  }

  def drain[F[_],A,B](a: NF[F,A]): NF[F,B] = NF(a.cleanup, a.frame match {
    case Fail(e) => Fail(e)
    case Emits(_) => Emits(Chunk.empty)
    case Await(g) => Await(g map drain)
    case Acquire(g) => Acquire(g map { case (f,a) => (f,drain(a)) })
    case Cons(_, t) => Cons(Chunk.empty, () => drain(t()))
  })

  def mask[F[_],A](a: NF[F,A]): NF[F,A] =
    onError(a)(e => emits(Chunk.empty))

  def await[F[_],A](a: NF[F,A]): NF[F, Step[Chunk[A], NF[F,A]]] = NF(a.cleanup, a.frame match {
    case Fail(e) => Fail(e)
    case Emits(c) => Emits(Chunk.singleton(Step(c, emits(Chunk.empty))))
    case Await(f) => Await(f map await)
    case Acquire(f) => Acquire(f map { case (r,a) => (r, await(a)) })
    case Cons(h, t) => Emits(Chunk.singleton(Step(h, t())))
  })

  def awaitAsync[F[_],A](a: NF[F,A])(implicit F: Async[F]): NF[F, F[NF[F, Step[Chunk[A], NF[F,A]]]]] =
    NF(a.cleanup, a.frame match {
      case Fail(e) => Fail(e)
      case Emits(c) => Emits(Chunk.singleton(F.pure { emit(Step(c, emits(Chunk.empty))) }))
      case Cons(h, t) => Emits(Chunk.singleton(F.pure { emit(Step(h, t())) }))
      case Await(f) => Await { Free.Eval {
        F.bind(F.ref[NF[F,A]]) { q =>
          F.map(F.setFree(q)(f)) { _ =>
            emit[F,F[NF[F,Step[Chunk[A], NF[F,A]]]]](F.pure(flatMap(eval(F.get(q)))(await))) }
        }
      }}
      case Acquire(r) => Acquire { Free.Eval {
        F.bind(F.ref[(F[Unit], NF[F,A])]) { q =>
        F.map(F.setFree(q)(r)) { _ =>
          val nf: F[NF[F,A]] = F.map(F.get(q)) { case (_,nf) => nf }
          // even though `r` has not completed, we can obtain a stable
          // reference to what the finalizer will be eventually
          val f: F[Unit] = F.bind(F.get(q)) { case (f,_) => f }
          (f, scope(Finalizers.single(f)) {
            emit[F,F[NF[F,Step[Chunk[A], NF[F,A]]]]](F.pure(flatMap(eval(nf))(await)))
          })
        }
      }}}
    })

  def runFold[F[_],A,B](p: NF[F,A], z: B)(f: (B, A) => B): Free[F,Either[Throwable,B]] = {
    def go[A,B](prev: Finalizers[F], p: NF[F,A], z: B)(f: (B, A) => B): Free[F,Either[Throwable,B]] =
      // at each step, we run any finalizers that have passed out of scope from previous step
      // and we also run any leftover finalizers when reaching the end of the stream
      prev.runDeactivated(p.cleanup) flatMap { _ =>
        p.frame match {
          // NB: this is guaranteed not to overlap with `runDeactivated`, via definition of
          // `runDeactivated`, which won't include any finalizers in `p.cleanup`
          case Fail(e) => p.cleanup.run flatMap { _ => Free.Pure(Left(e)) }
          case Emits(c) => p.cleanup.run flatMap { _ => Free.Pure(Right(c.foldLeft(z)(f))) }
          case Cons(h, t) => go(p.cleanup, t(), h.foldLeft(z)(f))(f)
          case Await(g) => g flatMap (p2 => go(p.cleanup, p2, z)(f))
          case Acquire(g) => g flatMap { case (_, p2) => go(p.cleanup, p2, z)(f) }
        }
      }
    go(Finalizers.empty, p, z)(f)
  }
}
