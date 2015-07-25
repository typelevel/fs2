package streams

import collection.immutable.SortedMap
import collection.immutable.SortedSet
import NF.ID

private trait S[F[_]] { type f[x] = NF[F,x] }

sealed trait NF[+F[_],+A] {

  private[NF] def nf[F2[x]>:F[x],A2>:A,A3,B](
    cleanup: SortedMap[ID,F2[Unit]], fresh: ID,
    k: Chain[S[F2]#f,A2,A3],
    z: B)(f: (B, A3) => B): Free[F2,Either[Throwable,B]] = ???

  private[NF] def await: Pull[F,Step[Chunk[A],Handle[F,A]],Nothing] = ???
}

object NF extends Stream[NF] {

  type Pull[+F[_],+R,+O] = streams.Pull[F,R,O]
  type Handle[+F[_],+A] = streams.Handle[F,A]
  type ID = Long

  private case class Emits[A](c: Chunk[A]) extends NF[Nothing,A] {
    type F[x] = Nothing
    private[NF] def nf[F2[x]>:F[x],A2>:A,A3,B](
      cleanup: SortedMap[ID,F2[Unit]], fresh: ID,
      k: Chain[S[F2]#f,A2,A3],
      z: B)(f: (B, A3) => B): Free[F2,Either[Throwable,B]]
      =
      k.uncons match {
        case Left((eqL,_)) => Free.pure(Right(c.foldLeft(z)((b,a2) => f(b, eqL(a2)))))
        case Right((kh,kt)) => c.uncons match {
          case None => Free.pure(Right(z))
          case Some((ch,ct)) => kh(ch).nf(cleanup, fresh, kt, z)(f) flatMap {
            case Left(e) => Free.pure(Left(e))
            case Right(z) => emits(ct).nf(cleanup, fresh, k, z)(f)
          }
        }
        case _ => ??? // not possible
      }
  }
  private case object Fresh extends NF[Nothing,ID]
  private case class Append[F[_],A](s1: NF[F,A], s2: () => NF[F,A]) extends NF[F,A]
  private case class Bind[F[_],R,A](r: NF[F,R], f: R => NF[F,A]) extends NF[F,A]
  private case class Acquire[F[_],R](id: ID, f: Free[F, (R, F[Unit])]) extends NF[F,R]
  private[streams] case class Release(id: ID) extends NF[Nothing,Nothing]
  private[streams] case class Fail(err: Throwable) extends NF[Nothing,Nothing]
  private case class Eval[F[_],A](f: F[A]) extends NF[F,A]
  private case class OnError[F[_],A](inner: NF[F,A], handle: Throwable => NF[F,A])
    extends NF[F,A]
  //  def done(cleanup: SortedMap[ID,F[Unit]]): Free[F, Unit] =
  //    cleanup.values.foldLeft[Free[F,Unit]](Free.pure(())) { (tl,hd) =>
  //      Free.Eval(hd) flatMap { _ => tl }
  //    }

  def emits[A](c: Chunk[A]): NF[Nothing,A] = Emits(c)
  def fail(e: Throwable): NF[Nothing,Nothing] = Fail(e)
  def eval[F[_],A](f: F[A]): NF[F,A] = Eval(f)
  def append[F[_],A](a1: NF[F,A], a2: => NF[F,A]): NF[F,A] = Append(a1, () => a2)
  def flatMap[F[_],A,B](a: NF[F,A])(f: A => NF[F,B]): NF[F,B] = Bind(a, f)
  def bracket[F[_],R,A](resource: F[R])(use: R => NF[F,A], release: R => F[Unit]): NF[F,A] =
    Fresh flatMap { rid =>
      Acquire(rid, Free.Eval(resource) map { r => (r, release(r)) }) flatMap { r =>
        lazy val cleanup = release(r)
        onComplete(use(r), eval_(cleanup))
      }
    }
  def onError[F[_], A](p: NF[F,A])(handle: Throwable => NF[F,A]): NF[F,A] =
    OnError(p, handle)

  def runFold[F[_],A,B](p: NF[F,A], z: B)(f: (B, A) => B): Free[F,Either[Throwable,B]] =
    p.nf(SortedMap.empty, 0,
         Chain.single[S[F]#f,A,Nothing](a => empty),
         z)(f)

  //implicit def covary[F[_],A](nf: NF[Nothing,A]): NF[F,A] =
  //  nf.asInstanceOf[NF[F,A]]
  //implicit def covary2[F[_],F2[x]>:F[x],A](nf: NF[F,A]): NF[F2,A] =
  //  nf.asInstanceOf[NF[F2,A]]

  def pullMonad[F[_],O] = new Monad[({ type f[x] = Pull[F,x,O]})#f] {
    def pure[R](r: R) = Pull.Pure(r)
    def bind[R,R2](r: Pull[F,R,O])(f: R => Pull[F,R2,O]) = Pull.Bind(r, f)
  }

  def open[F[_],A](s: NF[F,A]): Pull[F,Handle[F,A],Nothing] =
    Pull.Pure(new Handle(s))

  def emits[F[_], O](p: NF[F,O]): Pull[F,Unit,O] = Pull.Out(p)

  def runPull[F[_], R, O](p: Pull[F,R,O]): NF[F,O] =
    p.nf(SortedSet.empty, Chain.single[P[F,O]#f,R,Nothing](_ => Pull.done))

  // need to move this to a function on NF!
  def await[F[_],A](h: Handle[F,A]): Pull[F,Step[Chunk[A],Handle[F,A]],Nothing] = ???

  //    case Fail(e) => Pull.Done(Some(e))
  //    case Emits(c) => Pull.Pure(Step(c, new Handle[F,A](empty[Nothing])))
  //    case Cons(c, t) => Pull.Pure(Step(c, new Handle[F,A](t())))
  //    case Release(rid, tl) => pullMonad.bind (
  //      emits[F,Nothing](Release(rid, () => empty[Nothing]))) { _ =>
  //        await(new Handle(tl()))
  //      }
  //    case Await(g) => Pull.Await[F,Step[Chunk[A],Handle[F,A]],Nothing] {
  //      g map (s => await(new Handle(s)))
  //    }
  //    case Acquire(g) =>
  //      Pull.Await[F,Step[Chunk[A],Handle[F,A]],Nothing] {
  //        ???
  //        // g map { case (f,a) => (f, (rid: ID) => ???) }
  //      }
  //  }

}

private trait P[F[_],O] { type f[r] = Pull[F,r,O] }

sealed trait Pull[+F[_],+R,+O] {
  private[streams] def nf[F2[x]>:F[x],O2>:O,R2>:R](
    tracked: SortedSet[ID],
    k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
}

class Handle[+F[_],+A](private[streams] val stream: NF[F,A])

object Pull {
  val done = Done(None)
  case class Done(err: Option[Throwable]) extends Pull[Nothing,Nothing,Nothing] {
    type F[x] = Nothing; type O = Nothing; type R = Nothing
    def nf[F2[x]>:F[x],O2>:O,R2>:R](
      tracked: SortedSet[ID],
      k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
      =
      err match {
        case None => tracked.foldLeft[NF[F2,O2]](NF.empty[O2])((t,h) => NF.Release(h) ++ t)
        case Some(e) => tracked.foldLeft[NF[F2,O2]](NF.fail(e))((t,h) => NF.Release(h) ++ t)
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
  case class Eval[F[_],R,O](p: F[R]) extends Pull[F,R,O] {
    def nf[F2[x]>:F[x],O2>:O,R2>:R](
      tracked: SortedSet[ID],
      k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
      =
      NF.drain { NF.eval (p) }
  }
  case class Out[F[_],O](s: NF[F,O]) extends Pull[F,Unit,O] {
    type R = Unit
    def nf[F2[x]>:F[x],O2>:O,R2>:R](
      tracked: SortedSet[ID],
      k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
      =
      k.step(()) match {
        case None => done.nf(tracked, k)
        // todo: if tracked.size > 1000, may want to look through s for release of tracked
        case Some((p,k)) => NF.append(
          NF.onError(s: NF[F2,O2])(e => NF.append(done.nf(tracked, k), NF.fail(e))),
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
      NF.suspend { p.nf(tracked, f +: k) }
  }
}
