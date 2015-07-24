package streams

import collection.immutable.SortedMap
import collection.immutable.SortedSet

sealed trait NF[F[_],+A]

object NF extends Stream[NF] {

  private type ID = Long

  private case class Emits[F[_],A](c: Chunk[A]) extends NF[F,A]
  private case class Cons[F[_],A](hd: Chunk[A], a2: () => NF[F,A]) extends NF[F,A]
  private case class Fail[F[_],A](err: Throwable) extends NF[F,A]
  private case class Await[F[_],A](f: Free[F,NF[F,A]]) extends NF[F,A]
  private case class Acquire[F[_],A](f: Free[F,(F[Unit], ID => NF[F,A])]) extends NF[F,A]
  private case class Release[F[_],A](id: ID, tail: () => NF[F,A]) extends NF[F,A]

  def emits[A](c: Chunk[A]): NF[Nothing,A] =
    Emits[Nothing,A](c)
  def fail(e: Throwable): NF[Nothing,Nothing] =
    Fail[Nothing,Nothing](e)
  def free[F[_],A](f: Free[F,NF[F,A]]): NF[F,A] =
    Await(f)

  def append[F[_],A](a1: NF[F,A], a2: => NF[F,A]): NF[F,A] = a1 match {
    case Emits(c) => Cons(c, () => a2)
    case Cons(h,t) => Cons(h, () => append(t(),a2))
    case Fail(e) => Fail(e)
    case Await(f) => Await(f map (a1 => append(a1, a2)))
    case Acquire(r) => Acquire(r map { case (release, a1) => (release, id => append(a1(id),a2)) })
    case Release(id, tail) => Release(id, () => append(tail(), a2))
  }

  implicit def covary[F[_],A](nf: NF[Nothing,A]): NF[F,A] =
    nf.asInstanceOf[NF[F,A]]
  implicit def covary2[F[_],F2[x]>:F[x],A](nf: NF[F,A]): NF[F2,A] =
    nf.asInstanceOf[NF[F2,A]]

  def flatMap[F[_],A,B](a: NF[F,A])(f: A => NF[F,B]): NF[F,B] = {
    def go(a: NF[F,A])(f: A => NF[F,B]): NF[F,B] = a match {
      case Fail(e) => fail(e)
      case Emits(c) => c.uncons match {
        case None => empty[B]
        case Some((hd,tl)) => append(f(hd), go(emits(tl))(f))
      }
      case Cons(h, t) => append(go(emits(h))(f), go(t())(f))
      case Await(g) => Await(g map (a => go(a)(f)))
      case Acquire(r) => Acquire(r map { case (release, a) =>
        (release, id => go(a(id))(f)) })
      case Release(rid, tl) => Release(rid, () => go(tl())(f))
    }
    go(a)(f)
  }

  private def onComplete[F[_],A](p: NF[F,A], regardless: => NF[F,A]): NF[F,A] =
    onError(append(p, mask(regardless))) { err => append(mask(regardless), fail(err)) }

  def bracket[F[_],R,A](acquire: F[R])(use: R => NF[F,A], release: R => F[Unit]): NF[F,A] =
    Acquire(Free.Eval(acquire) map { r =>
      lazy val cleanup = release(r)
      val body = (rid: ID) => onComplete(use(r), Release[F,A](rid, () => empty[A]))
      (cleanup, body)
    })

  def onError[F[_],A](a: NF[F,A])(handle: Throwable => NF[F,A]): NF[F,A] = a match {
    // keeps `a.cleanup` in scope for duration of handler
    case Fail(e) => handle(e)
    case Emits(_) => a
    case Await(g) => Await(g map (a => onError(a)(handle)))
    case Acquire(g) => Acquire(g map { case (f,a) => (f, rid => onError(a(rid))(handle)) })
    case Cons(h, t) => Cons(h, () => onError(t())(handle))
    case Release(rid, tl) => Release(rid, () => onError(tl())(handle))
  }

  def drain[F[_],A](a: NF[F,A]): NF[F,Nothing] = a match {
    case Fail(e) => Fail(e)
    case Emits(_) => empty[Nothing]
    case Await(g) => Await(g map drain)
    case Acquire(g) => Acquire(g map { case (f,a) => (f,rid => drain(a(rid))) })
    case Release(rid, tl) => Release(rid, () => drain(tl()))
    case Cons(_, t) => Cons(Chunk.empty, () => drain(t()))
  }

  def mask[F[_],A](a: NF[F,A]): NF[F,A] =
    onError(a)(_ => empty[A])

  class Handle[F[_],+A](private[NF] val stream: NF[F,A])

  def pullMonad[F[_],O] = new Monad[({ type f[x] = Pull[F,x,O]})#f] {
    def pure[R](r: R) = Pull.Pure(r)
    def bind[R,R2](r: Pull[F,R,O])(f: R => Pull[F,R2,O]) = Pull.Bind(r, f)
  }

  def open[F[_],A](s: NF[F,A]): Pull[F,Handle[F,A],Nothing] =
    Pull.Pure(new Handle(s))

  def emits[F[_], O](p: NF[F,O]): Pull[F,Unit,O] = Pull.Out(p)

  def runPull[F[_], R, O](p: Pull[F,R,O]): NF[F,O] =
    p.nf(SortedSet.empty, Chain.single[P[F,O]#f,R,Nothing](_ => Pull.done))

  def await[F[_],A](h: Handle[F,A]): Pull[F,Step[Chunk[A],Handle[F,A]],Nothing] =
    h.stream match {
      case Fail(e) => Pull.Done(Some(e))
      case Emits(c) => Pull.Pure(Step(c, new Handle[F,A](empty[Nothing])))
      case Cons(c, t) => Pull.Pure(Step(c, new Handle[F,A](t())))
      case Release(rid, tl) => pullMonad.bind (
        emits[F,Nothing](Release(rid, () => empty[Nothing]))) { _ =>
          await(new Handle(tl()))
        }
      case Await(g) => Pull.Await[F,Step[Chunk[A],Handle[F,A]],Nothing] {
        g map (s => await(new Handle(s)))
      }
      case Acquire(g) =>
        Pull.Await[F,Step[Chunk[A],Handle[F,A]],Nothing] {
          ???
          // g map { case (f,a) => (f, (rid: ID) => ???) }
        }
    }

  private trait P[F[_],O] { type f[r] = Pull[F,r,O] }

  sealed trait Pull[+F[_],+R,+O] {
    private[NF] def nf[F2[x]>:F[x],O2>:O,R2>:R](
      tracked: SortedSet[ID],
      k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
  }

  object Pull {
    val done = Done(None)
    case class Done(err: Option[Throwable]) extends Pull[Nothing,Nothing,Nothing] {
      type F[x] = Nothing; type O = Nothing; type R = Nothing
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        err match {
          case None => tracked.foldLeft[NF[F2,O2]](empty[O2])((t,h) => Release(h, () => t))
          case Some(e) => tracked.foldLeft[NF[F2,O2]](fail(e))((t,h) => Release(h, () => t))
        }
    }
    case class Track(resourceID: ID) extends Pull[Nothing,Unit,Nothing] {
      type F[x] = Nothing; type O = Nothing; type R = Unit
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        k.step(()) match {
          case None => done.nf(tracked, k)
          case Some((p,k)) => p.nf(tracked + resourceID, k)
          case _ => ??? // not possible
        }
    }
    case class Await[F[_],R,O](p: Free[F, Pull[F,R,O]]) extends Pull[F,R,O] {
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        free (p map { _.nf(tracked, k) })
    }
    //case class Acquire[F[_],R,O](f: Free[F,(F[Unit], ID => Pull[F,R,O])]) extends Pull[F,R,Nothing] {
    //  type F[x] = Nothing; type O = Nothing; type R = Unit
    //  def nf[F2[x]>:F[x],O2>:O,R2>:R](
    //    tracked: SortedSet[ID],
    //    k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
    //    =
    //    k.step(()) match {
    //      case None => done.nf(tracked, k)
    //      case Some((p,k)) => p.nf(tracked + resourceID, k)
    //      case _ => ??? // not possible
    //    }
    //}
    case class Out[F[_],O](s: NF[F,O]) extends Pull[F,Unit,O] {
      type R = Unit
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        k.step(()) match {
          case None => done.nf(tracked, k)
          // todo: if tracked.size > 1000, may want to look through s for release of tracked
          case Some((p,k)) => append(
            onError(covary2[F,F2,O2](s))(e => append(done.nf(tracked, k), fail(e))),
            p.nf(tracked, k))
          case _ => ??? // not possible
        }
    }
    case class Pure[R](r: R) extends Pull[Nothing,R,Nothing] {
      type F[x] = Nothing; type O = Nothing
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        k.step(r) match {
          case None => done.nf(tracked, k)
          case Some((p,k)) => p.nf(tracked, k)
          case _ => ??? // not possible
        }
    }
    case class Bind[F[_],R0,R,O](p: Pull[F,R0,O], f: R0 => Pull[F,R,O]) extends Pull[F,R,O] {
      def nf[F2[x]>:F[x],O2>:O,R2>:R](
        tracked: SortedSet[ID],
        k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
        =
        suspend { p.nf(tracked, f +: k) }
    }
  }

  def runFold[F[_],A,B](p: NF[F,A], z: B)(f: (B, A) => B): Free[F,Either[Throwable,B]] = {
    def go[A,B](cleanup: SortedMap[ID,F[Unit]], fresh: ID, p: NF[F,A], z: B)(f: (B, A) => B):
    Free[F,Either[Throwable,B]] = p match {
      case Fail(e) => done(cleanup) flatMap { _ => Free.Pure(Left(e)) }
      case Emits(c) => done(cleanup) flatMap { _ => Free.Pure(Right(c.foldLeft(z)(f))) }
      case Cons(h, t) => go(cleanup, fresh, t(), h.foldLeft(z)(f))(f)
      case Await(g) => g flatMap (p2 => go(cleanup, fresh, p2, z)(f))
      case Acquire(g) => g flatMap { case (action, p2) =>
        go(cleanup.updated(fresh, action), fresh + 1, p2(fresh), z)(f) }
      case Release(rid, tl) =>
        cleanup.get(rid) match {
          case None =>
            println("Invalid resource ID: " + rid)
            go(cleanup, fresh, tl(), z)(f) // this is not an error
          case Some(action) => Free.eval(action) flatMap { _ => go(cleanup, fresh, tl(), z)(f) }
        }
    }
    // run finalizers for later resources first
    def done(cleanup: SortedMap[ID,F[Unit]]): Free[F, Unit] =
      cleanup.values.foldLeft[Free[F,Unit]](Free.pure(())) { (tl,hd) =>
        Free.Eval(hd) flatMap { _ => tl }
      }
    go(SortedMap.empty, 0, p, z)(f)
  }
}
