package streams

/*
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
    def nf[F2[x]>:F[x],A2>:A,A3,B](
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
    def await = Pull.Pure(Step(c, new Handle(empty)))
  }
  private case object Fresh extends NF[Nothing,ID] {
    type F[x] = Nothing; type A = ID
    def await = ???
  }
  private case class Append[F[_],A](s1: NF[F,A], s2: () => NF[F,A]) extends NF[F,A] {
    def await =
      s1.await.map { case Step(h,t) => Step(h, new Handle(t.stream ++ s2())) }
  }
  private case class Bind[F[_],R,A](r: NF[F,R], f: R => NF[F,A]) extends NF[F,A]
  private case class Acquire[F[_],R](id: ID, f: Free[F, (R, F[Unit])]) extends NF[F,R]
  private[streams] case class Release(id: ID) extends NF[Nothing,Nothing]
  private case class Fail(err: Throwable) extends NF[Nothing,Nothing]
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

  def pullMonad[F[_],O] = Pull.monad[F,O]

  def open[F[_],A](s: NF[F,A]): Pull[F,Handle[F,A],Nothing] =
    Pull.Pure(new Handle(s))

  def emits[F[_], O](p: NF[F,O]): Pull[F,Unit,O] = Pull.Out(p)

  def runPull[F[_], R, O](p: Pull[F,R,O]): NF[F,O] =
    p.nf(SortedSet.empty, Chain.single[P[F,O]#f,R,Nothing](_ => Pull.done))

  def await[F[_],A](h: Handle[F,A]): Pull[F,Step[Chunk[A],Handle[F,A]],Nothing] =
    h.stream.await
}

private trait P[F[_],O] { type f[r] = Pull[F,r,O] }

sealed trait Pull[+F[_],+R,+O] {
  private[streams] def nf[F2[x]>:F[x],O2>:O,R2>:R](
    tracked: SortedSet[ID],
    k: Chain[P[F2,O2]#f,R2,Nothing]): NF[F2,O2]
}

class Handle[+F[_],+A](private[streams] val stream: NF[F,A])

object Pull {
  // idea: just combine NF and Pull into a single type
  // avoid awkward conversion between the two
  // class Process[+F[_],+A](pull: Pull[F,Nothing,A])
  // class Handle[+F[_],+A](process: Process[F,A])
  /*
  data Pull f r o where
    Pure : r -> Pull f r o
    Eval : f r -> Pull f r o
    Bind : Pull f r0 o -> (r -> Pull f r o) -> Pull f r o
    Done : Maybe Err -> Pull f r o
    Write : [o] -> Pull f () o
    ConcatMap : Pull f r o -> (o -> Pull f r o) ->
    Append : Pull f r o -> Pull f r o -> Pull f r o
    Fresh : Pull f ID o
    Acquire : ID -> f r -> (r -> f ()) -> Pull f r o
    Release : ID -> Pull f () o

  newtype Process f a = Process (Pull f Void a)
  newtype Handle f a = Handle (Process f a)

  awaitHandle (Handle (Process p)) = case p of

  runFold : b -> (b -> a -> b) -> Process f a -> Free f (Either Err b)
  runPull : Pull f r o -> Process f o
  await : Process f a -> Pull f (Step [a] (Handle f a)) o

  // try implementing runFold
  // try implementing await
  */


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

  def monad[F[_],O]: Monad[P[F,O]#f] = new Monad[P[F,O]#f] {
    def pure[R](r: R) = Pure(r)
    def bind[R,R2](r: Pull[F,R,O])(f: R => Pull[F,R2,O]) = Bind(r, f)
  }
}
*/
