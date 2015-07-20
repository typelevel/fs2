package streams

import scodec.bits.ByteVector

sealed trait Chunk[+A] {
  def size: Int
  def uncons: Option[(A, Chunk[A])] =
    if (size == 0) None
    else Some(apply(0) -> drop(1))
  def apply(i: Int): A
  def drop(n: Int): Chunk[A]
  def foldLeft[B](z: B)(f: (B,A) => B): B
}

object Chunk {
  val empty: Chunk[Nothing] = new Chunk[Nothing] {
    def size = 0
    def apply(i: Int) = throw new IllegalArgumentException(s"Chunk.empty($i)")
    def drop(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,Nothing) => B): B = z
  }
  def singleton[A](a: A): Chunk[A] = new Chunk[A] {
    def size = 1
    def apply(i: Int) = if (i == 0) a else throw new IllegalArgumentException(s"Chunk.singleton($i)")
    def drop(n: Int) = empty
    def foldLeft[B](z: B)(f: (B,A) => B): B = f(z,a)
  }
  def array[A](a: Array[A], offset: Int = 0): Chunk[A] = new Chunk[A] {
    def size = a.length - offset
    def apply(i: Int) = a(offset + i)
    def drop(n: Int) = if (n >= size) empty else array(a, offset + n)
    def foldLeft[B](z: B)(f: (B,A) => B): B = {
      var res = z
      (offset until a.length).foreach { i => res = f(res, a(i)) }
      res
    }
  }
  def seq[A](a: Seq[A]): Chunk[A] = new Chunk[A] {
    def size = a.size
    def apply(i: Int) = a(i)
    def drop(n: Int) = seq(a.drop(n))
    def foldLeft[B](z: B)(f: (B,A) => B): B = a.foldLeft(z)(f)
  }
}
case class Step[+A,+B](head: A, tail: B)

trait Stream[P[+_[_],+_]] { self =>

  // list-like operations

  def empty[A]: P[Nothing,A] = emits(Chunk.empty)

  def emits[F[_],A](as: Chunk[A]): P[F,A]

  def append[F[_],A](a: P[F,A], b: => P[F,A]): P[F,A]

  def flatMap[F[_],A,B](a: P[F,A])(f: A => P[F,B]): P[F,B]


  // evaluating effects

  def free[F[_],A](fa: Free[F,P[F,A]]): P[F,A]


  // failure and error recovery

  def fail[F[_],A](e: Throwable): P[F,A]

  def onError[F[_],A](p: P[F,A])(handle: Throwable => P[F,A]): P[F,A]


  // stepping a stream

  def await[F[_],A](p: P[F, A]): P[F, Step[Chunk[A], P[F,A]]]

  def awaitAsync[F[_]:Async,A](p: P[F, A]): P[F, AsyncStep[F,A]]

  type AsyncStep[F[_],A] = F[P[F, Step[Chunk[A], P[F,A]]]]
  type AsyncStep1[F[_],A] = F[P[F, Step[A, P[F,A]]]]

  // resource acquisition

  def bracket[F[_]:Affine,R,A](acquire: F[R])(use: R => P[F,A], release: R => F[Unit]): P[F,A]


  // evaluation

  def runFold[F[_],A,B](p: P[F,A], z: B)(f: (B,A) => B): Free[F,Either[Throwable,B]]


  // derived operations

  def map[F[_],A,B](a: P[F,A])(f: A => B): P[F,B] =
    flatMap(a)(f andThen (emit))

  def emit[F[_],A](a: A): P[F,A] = emits(Chunk.singleton(a))

  def force[F[_],A](f: F[P[F, A]]): P[F,A] =
    flatMap(eval(f))(p => p)

  def await1[F[_],A](p: P[F,A]): P[F, Step[A, P[F,A]]] = flatMap(await(p)) { step =>
    step.head.uncons match {
      case None => empty
      case Some((hd,tl)) => emit { Step(hd, append(emits(tl), step.tail)) }
    }
  }

  def await1Async[F[_],A](p: P[F, A])(implicit F: Async[F]): P[F, F[P[F, Step[A, P[F,A]]]]] =
    map(awaitAsync(p)) { futureStep =>
      F.map(futureStep) { steps => steps.flatMap { step => step.head.uncons match {
        case None => empty
        case Some((hd,tl)) => emit { Step(hd, append(emits(tl), step.tail)) }
      }}}
    }

  def eval[F[_],A](fa: F[A]): P[F,A] = free(Free.Eval(fa) flatMap (a => Free.Pure(emit(a))))

  def eval_[F[_],A](fa: F[A]): P[F,Nothing] =
    flatMap(eval(fa)) { _ => empty }

  def terminated[F[_],A](p: P[F,A]): P[F,Option[A]] =
    p.map(Some(_)) ++ emit(None)

  def mergeHaltBoth[F[_],A](p: P[F,A], p2: P[F,A])(implicit F: Async[F]): P[F,A] = {
    def go(f1: AsyncStep[F,A], f2: AsyncStep[F,A]): P[F,A] =
      eval(F.race(f1,f2)) flatMap {
        case Left(p) => p flatMap { p =>
          emits(p.head) ++ awaitAsync(p.tail).flatMap(go(_,f2))
        }
        case Right(p2) => p2 flatMap { p2 =>
          emits(p2.head) ++ awaitAsync(p2.tail).flatMap(go(f1,_))
        }
      }
    awaitAsync(p)  flatMap  { f1 =>
    awaitAsync(p2) flatMap  { f2 => go(f1,f2) }}
  }

  implicit class Syntax[+F[_],+A](p1: P[F,A]) {
    def map[B](f: A => B): P[F,B] =
      self.map(p1)(f)

    def flatMap[F2[x]>:F[x],B](f: A => P[F2,B]): P[F2,B] =
      self.flatMap(p1: P[F2,A])(f)

    def ++[F2[x]>:F[x],B>:A](p2: P[F2,B])(implicit R: RealSupertype[A,B]): P[F2,B] =
      self.append(p1: P[F2,B], p2)

    def append[F2[x]>:F[x],B>:A](p2: P[F2,B])(implicit R: RealSupertype[A,B]): P[F2,B] =
      self.append(p1: P[F2,B], p2)

    def await1: P[F, Step[A, P[F,A]]] = self.await1(p1)

    def await: P[F, Step[Chunk[A], P[F,A]]] = self.await(p1)

    def awaitAsync[F2[x]>:F[x],B>:A](implicit F2: Async[F2], R: RealSupertype[A,B]):
      P[F2, AsyncStep[F2,B]] =
      self.awaitAsync(p1: P[F2,B])

    def await1Async[F2[x]>:F[x],B>:A](implicit F2: Async[F2], R: RealSupertype[A,B]):
      P[F2, AsyncStep1[F2,B]] =
      self.await1Async(p1)
  }
}

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

import java.util.UUID

private[streams]
class Finalizers[F[_]](private[Finalizers] val order: Vector[UUID],
                       private[Finalizers] val actions: Map[UUID, F[Unit]]) {

  def isEmpty = order.isEmpty

  def append(f2: Finalizers[F]): Finalizers[F] =
    if (order.isEmpty) f2
    else new Finalizers(order ++ f2.order, actions ++ f2.actions)

  def runDeactivated(f2: Finalizers[F]): Free[F,Unit] = {
    // anything which has dropped out of scope is considered deactivated
    val order2 = order.filter(id => !f2.actions.contains(id))
    order2.foldRight(Free.Pure(()): Free[F,Unit])((f,acc) => Free.Eval(actions(f)) flatMap { _ => acc })
  }

  def run: Free[F,Unit] =
    order.foldRight(Free.Pure(()): Free[F,Unit])((f,acc) => Free.Eval(actions(f)) flatMap { _ => acc })
}

object Finalizers {
  def single[F[_]](f: F[Unit]): Finalizers[F] = {
    val id = UUID.randomUUID
    new Finalizers(Vector(id), Map(id -> f))
  }
  def empty[F[_]]: Finalizers[F] = new Finalizers(Vector(), Map())
}
