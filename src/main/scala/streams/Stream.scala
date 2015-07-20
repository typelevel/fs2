package streams

trait Stream[S[+_[_],+_]] { self =>

  // list-like operations

  def empty[A]: S[Nothing,A] = emits(Chunk.empty)

  def emits[F[_],A](as: Chunk[A]): S[F,A]

  def append[F[_],A](a: S[F,A], b: => S[F,A]): S[F,A]

  def flatMap[F[_],A,B](a: S[F,A])(f: A => S[F,B]): S[F,B]


  // evaluating effects

  def free[F[_],A](fa: Free[F,S[F,A]]): S[F,A]


  // failure and error recovery

  def fail[F[_],A](e: Throwable): S[F,A]

  def onError[F[_],A](p: S[F,A])(handle: Throwable => S[F,A]): S[F,A]


  // stepping a stream

  def await[F[_],A](p: S[F, A]): S[F, Step[Chunk[A], S[F,A]]]

  def awaitAsync[F[_]:Async,A](p: S[F, A]): S[F, AsyncStep[F,A]]

  type AsyncStep[F[_],A] = F[S[F, Step[Chunk[A], S[F,A]]]]
  type AsyncStep1[F[_],A] = F[S[F, Step[A, S[F,A]]]]

  // resource acquisition

  def bracket[F[_]:Affine,R,A](acquire: F[R])(use: R => S[F,A], release: R => F[Unit]): S[F,A]


  // evaluation

  def runFold[F[_],A,B](p: S[F,A], z: B)(f: (B,A) => B): Free[F,Either[Throwable,B]]


  // derived operations

  def map[F[_],A,B](a: S[F,A])(f: A => B): S[F,B] =
    flatMap(a)(f andThen (emit))

  def emit[F[_],A](a: A): S[F,A] = emits(Chunk.singleton(a))

  def force[F[_],A](f: F[S[F, A]]): S[F,A] =
    flatMap(eval(f))(p => p)

  def await1[F[_],A](p: S[F,A]): S[F, Step[A, S[F,A]]] = flatMap(await(p)) { step =>
    step.head.uncons match {
      case None => empty
      case Some((hd,tl)) => emit { Step(hd, append(emits(tl), step.tail)) }
    }
  }

  def await1Async[F[_],A](p: S[F, A])(implicit F: Async[F]): S[F, AsyncStep1[F,A]] =
    map(awaitAsync(p)) { futureStep =>
      F.map(futureStep) { steps => steps.flatMap { step => step.head.uncons match {
        case None => empty
        case Some((hd,tl)) => emit { Step(hd, append(emits(tl), step.tail)) }
      }}}
    }

  def eval[F[_],A](fa: F[A]): S[F,A] = free(Free.Eval(fa) flatMap (a => Free.Pure(emit(a))))

  def eval_[F[_],A](fa: F[A]): S[F,Nothing] =
    flatMap(eval(fa)) { _ => empty }

  def terminated[F[_],A](p: S[F,A]): S[F,Option[A]] =
    p.map(Some(_)) ++ emit(None)

  def mergeHaltBoth[F[_],A](p: S[F,A], p2: S[F,A])(implicit F: Async[F]): S[F,A] = {
    def go(f1: AsyncStep[F,A], f2: AsyncStep[F,A]): S[F,A] =
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

  implicit class Syntax[+F[_],+A](p1: S[F,A]) {
    def map[B](f: A => B): S[F,B] =
      self.map(p1)(f)

    def flatMap[F2[x]>:F[x],B](f: A => S[F2,B]): S[F2,B] =
      self.flatMap(p1: S[F2,A])(f)

    def ++[F2[x]>:F[x],B>:A](p2: S[F2,B])(implicit R: RealSupertype[A,B]): S[F2,B] =
      self.append(p1: S[F2,B], p2)

    def append[F2[x]>:F[x],B>:A](p2: S[F2,B])(implicit R: RealSupertype[A,B]): S[F2,B] =
      self.append(p1: S[F2,B], p2)

    def await1: S[F, Step[A, S[F,A]]] = self.await1(p1)

    def await: S[F, Step[Chunk[A], S[F,A]]] = self.await(p1)

    def awaitAsync[F2[x]>:F[x],B>:A](implicit F2: Async[F2], R: RealSupertype[A,B]):
      S[F2, AsyncStep[F2,B]] =
      self.awaitAsync(p1: S[F2,B])

    def await1Async[F2[x]>:F[x],B>:A](implicit F2: Async[F2], R: RealSupertype[A,B]):
      S[F2, AsyncStep1[F2,B]] =
      self.await1Async(p1)

    def runFold[B](z: B)(f: (B,A) => B): Free[F,Either[Throwable,B]] =
      self.runFold(p1, z)(f)

    def runLog: Free[F,Either[Throwable,Vector[A]]] =
      self.runFold(p1, Vector.empty[A])(_ :+ _)
  }
}

