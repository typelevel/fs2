package fs2.util

import fs2.internal.Trampoline

/** A specialized free monad which captures exceptions thrown during evaluation. */
sealed trait Free[+F[_],+A] {
  import Free._
  final def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)
  final def map[B](f: A => B): Free[F,B] = Bind(this, (a: A) => Free.Pure(f(a)))

  final def fold[F2[_],G[_],A2>:A](f: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2] =
    this.step._fold(f)

  final def translate[G[_]](u: F ~> G): Free[G,A] = {
    type FG[x] = Free[G,x]
    fold[F,FG,A](new Fold[F,FG,A] {
      def suspend(g: => FG[A]) = Free.suspend(g)
      def done(a: A) = Free.pure(a)
      def fail(t: Throwable) = Free.fail(t)
      def eval[X](fx: F[X])(f: Attempt[X] => FG[A]) = Free.attemptEval(u(fx)).flatMap(f)
      def bind[X](x: X)(f: X => FG[A]) = f(x)
    })
  }

  final def attempt: Free[F,Attempt[A]] = attempt_(true)
  final def attemptStrict: Free[F,Attempt[A]] = attempt_(false)

  private def attempt_(trampoline: Boolean): Free[F,Attempt[A]] = {
    type G[x] = Free[F,Attempt[x]]
    fold[F,G,A](new Fold[F,G,A] {
      def suspend(g: => G[A]) = if (trampoline) Free.suspend(g) else g
      def done(a: A) = Free.pure(Right(a))
      def fail(t: Throwable) = Free.pure(Left(t))
      def eval[X](fx: F[X])(f: Attempt[X] => G[A]) = Free.attemptEval(fx) flatMap f
      def bind[X](x: X)(f: X => G[A]) = try f(x) catch { case NonFatal(t) => Free.pure(Left(t)) }
    })
  }

  protected def _fold[F2[_],G[_],A2>:A](f: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2]

  final def runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
    step._runTranslate(g)

  protected def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2]

  final def unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Unroll[A, G[Free[F,A]]] =
    this.step._unroll.run

  protected def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Trampoline[Unroll[A, G[Free[F,A]]]]

  final def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
    (this: Free[F2,A2]).runTranslate(UF1.id)

  @annotation.tailrec
  private[fs2] final def step: Free[F,A] = this match {
    case Bind(Bind(x, f), g) => (x flatMap (a => f(a) flatMap g)).step
    case _ => this
  }

  override def toString = "Free"
}

object Free {

  trait Fold[F[_],G[_],A] {
    def suspend(g: => G[A]): G[A]
    def done(a: A): G[A]
    def fail(t: Throwable): G[A]
    def eval[X](fx: F[X])(f: Attempt[X] => G[A]): G[A]
    def bind[X](x: X)(f: X => G[A]): G[A]
  }

  def attemptEval[F[_],A](a: F[A]): Free[F,Attempt[A]] = Eval(a)
  def fail(err: Throwable): Free[Nothing,Nothing] = Fail(err)
  def pure[A](a: A): Free[Nothing,A] = Pure(a)
  def attemptPure[A](a: => A): Free[Nothing,A] =
    try pure(a)
    catch { case NonFatal(e) => Fail(e) }
  def eval[F[_],A](a: F[A]): Free[F,A] = Eval(a) flatMap {
    case Left(e) => fail(e)
    case Right(a) => pure(a)
  }
  def suspend[F[_],A](fa: => Free[F,A]): Free[F,A] =
    pure(()) flatMap { _ => fa }

  private final case class Fail(err: Throwable) extends Free[Nothing,Nothing] {
    def _runTranslate[G[_],A2>:Nothing](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.fail(err)
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G])
    : Trampoline[Unroll[Nothing, G[Free[Nothing,Nothing]]]]
    = Trampoline.done { Unroll.Fail(err) }
    def _fold[F2[_],G[_],A2>:Nothing](f: Fold[F2,G,A2])(implicit S: Sub1[Nothing,F2], T: RealSupertype[Nothing,A2]): G[A2] = f.fail(err)
  }
  private final case class Pure[A](a: A) extends Free[Nothing,A] {
    def _runTranslate[G[_],A2>:A](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.pure(a)
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G])
    : Trampoline[Unroll[A, G[Free[Nothing,A]]]]
    = Trampoline.done { Unroll.Pure(a) }
    def _fold[F2[_],G[_],A2>:A](f: Fold[F2,G,A2])(implicit S: Sub1[Nothing,F2], T: RealSupertype[A,A2]): G[A2] = f.done(a)
  }
  private final case class Eval[F[_],A](fa: F[A]) extends Free[F,Attempt[A]] {
    def _runTranslate[G[_],A2>:Attempt[A]](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.attempt { g(fa) }.asInstanceOf[G[A2]]

    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
    : Trampoline[Unroll[Attempt[A], G[Free[F,Attempt[A]]]]]
    = Trampoline.done { Unroll.Eval(G.map(S(fa))(a => Free.pure(Right(a)))) }

    def _fold[F2[_],G[_],A2>:Attempt[A]](f: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[Attempt[A],A2]): G[A2] =
      f.eval(S(fa))(f.done)
  }
  private final case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A] {
    def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.flatMap(r._runTranslate(g))(r => f(r).runTranslate(g))
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
    : Trampoline[Unroll[A, G[Free[F,A]]]]
    = Sub1.substFree(r) match {
      case Pure(r) =>
        try Trampoline.suspend { f(r).step._unroll }
        catch { case NonFatal(err) => Trampoline.done { Unroll.Fail(err) } }
      case Fail(err) => Trampoline.done { Unroll.Fail(err) }
      case eval =>
        // NB: not bothering to convince Scala this is legit but since
        // `.step` returns a right-associated flatMap, and `Eval[F,A]` has type
        // Free[Attempt[A]], this is safe
        val ga: G[Any] = eval.asInstanceOf[Eval[G,Any]].fa
        val fr: Attempt[Any] => Free[F,A]
           = f.asInstanceOf[Attempt[Any] => Free[F,A]]
        Trampoline.done { Unroll.Eval(G.map(ga) { any => fr(Right(any)) }) }
    }
    def _fold[F2[_],G[_],A2>:A](fold: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2] =
      fold.suspend { Sub1.substFree(r) match {
        case Pure(r) => fold.bind(r) { r => f(r).fold(fold) }
        case Fail(err) => fold.fail(err)
        // NB: Scala won't let us pattern match on Eval here, but this is safe since `.step`
        // removes any left-associated flatMaps
        case eval => fold.eval(eval.asInstanceOf[Eval[F2,R]].fa)(a => f.asInstanceOf[Any => Free[F,A]](a).fold(fold))
      }}
  }

  sealed trait Unroll[+A,+B]
  object Unroll {
    final case class Fail(err: Throwable) extends Unroll[Nothing,Nothing]
    final case class Pure[A](a: A) extends Unroll[A,Nothing]
    final case class Eval[B](e: B) extends Unroll[Nothing,B]
  }

  implicit def monad[F[_]]: Monad[({ type f[x] = Free[F,x]})#f] =
    new Monad[({ type f[x] = Free[F,x]})#f] {
      def pure[A](a: A) = Pure(a)
      def flatMap[A,B](a: Free[F,A])(f: A => Free[F,B]) = a flatMap f
    }
}
