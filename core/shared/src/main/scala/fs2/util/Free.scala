package fs2.util

import fs2.internal.Trampoline

/** A specialized free monad which captures exceptions thrown during evaluation. */
sealed trait Free[+F[_],+A] {
  import Free._
  def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)

  def map[B](f: A => B): Free[F,B] = Map(this, Map.Stack.Base(f))

  def fold[F2[_],G[_],A2>:A](f: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2]

  final def translate[G[_]](u: F ~> G): Free[G,A] = {
    type FG[x] = Free[G,x]
    fold[F,FG,A](new Fold[F,FG,A] {
      def suspend(g: => FG[A]) = Free.suspend(g)
      def done(a: A) = Free.pure(a)
      def fail(t: Throwable) = Free.fail(t)
      def eval[X](fx: F[X])(f: Attempt[X] => FG[A]) = Free.attemptEval(u(fx)).flatMap(f)
      def map[X](x: X)(f: X => A) = Free.pure(f(x))
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
      def map[X](x: X)(f: X => A) = try Free.pure(Right(f(x))) catch { case NonFatal(t) => Free.pure(Left(t)) }
      def bind[X](x: X)(f: X => G[A]) = try f(x) catch { case NonFatal(t) => Free.pure(Left(t)) }
    })
  }

  final def unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Unroll[A, G[Free[F,A]]] =
    unroll_.run

  protected def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Trampoline[Unroll[A, G[Free[F,A]]]]

  def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2]

  override def toString = "Free(..)"
}

object Free {

  trait Fold[F[_],G[_],A] {
    def suspend(g: => G[A]): G[A]
    def done(a: A): G[A]
    def fail(t: Throwable): G[A]
    def eval[X](fx: F[X])(f: Attempt[X] => G[A]): G[A]
    def map[X](x: X)(f: X => A): G[A]
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
    def run[F2[x]>:Nothing, A2>:Nothing](implicit F2: Catchable[F2]): F2[A2] =
      F2.fail(err)
    def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G]): Trampoline[Unroll[Nothing, G[Free[Nothing,Nothing]]]] =
      Trampoline.done { Unroll.Fail(err) }
    def fold[F2[_],G[_],A2>:Nothing](f: Fold[F2,G,A2])(implicit S: Sub1[Nothing,F2], T: RealSupertype[Nothing,A2]): G[A2] =
      f.fail(err)
  }
  private final case class Pure[A](a: A) extends Free[Nothing,A] {
    def run[F2[x]>:Nothing, A2>:A](implicit F2: Catchable[F2]): F2[A2] =
      F2.pure(a)
    def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G]): Trampoline[Unroll[A, G[Free[Nothing,A]]]] =
      Trampoline.done { Unroll.Pure(a) }
    def fold[F2[_],G[_],A2>:A](f: Fold[F2,G,A2])(implicit S: Sub1[Nothing,F2], T: RealSupertype[A,A2]): G[A2] =
      f.done(a)
  }
  private final case class Eval[F[_],A](fa: F[A]) extends Free[F,Attempt[A]] {
    def run[F2[x]>:F[x], A2>:Attempt[A]](implicit F2: Catchable[F2]): F2[A2] =
      F2.attempt(fa).asInstanceOf[F2[A2]]
    def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Trampoline[Unroll[Attempt[A], G[Free[F,Attempt[A]]]]] =
      Trampoline.done { Unroll.Eval(G.map(S(fa))(a => Free.pure(Right(a)))) }
    def fold[F2[_],G[_],A2>:Attempt[A]](f: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[Attempt[A],A2]): G[A2] =
      f.eval(S(fa))(f.done)
  }
  private final case class Map[+F[_],R,A](r: Free[F,R], f: Map.Stack[R, A]) extends Free[F,A] {
    override def map[B](g: A => B): Free[F,B] =
      Map(r, Map.Stack.Base(g) compose f)
    override def flatMap[F2[x]>:F[x],B](g: A => Free[F2,B]): Free[F2,B] =
      Bind(r, f andThen g)
    def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
      F2.map(r.run(F2))(r => f(r))
    def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Trampoline[Unroll[A, G[Free[F,A]]]] = Sub1.substFree(r) match {
      case Pure(r) =>
        try Trampoline.done { Unroll.Pure(f(r)) }
        catch { case NonFatal(err) => Trampoline.done { Unroll.Fail(err) } }
      case Fail(err) => Trampoline.done { Unroll.Fail(err) }
      case eval =>
        val ga: G[Any] = eval.asInstanceOf[Eval[G,Any]].fa
        val fr: Attempt[Any] => A = f.asInstanceOf[Attempt[Any] => A]
        Trampoline.done { Unroll.Eval(G.map(ga)(a => pure(fr(Right(a))))) }
    }
    def fold[F2[_],G[_],A2>:A](fold: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2] =
      fold.suspend { Sub1.substFree(r) match {
        case Pure(r) => fold.map(r)(f)
        case Fail(err) => fold.fail(err)
        case eval =>
          val fa: F2[Any] = eval.asInstanceOf[Eval[F2,Any]].fa
          val fr: Attempt[Any] => A = f.asInstanceOf[Attempt[Any] => A]
          fold.eval(fa)(fr andThen fold.done)
      }}
  }
  private object Map {
    sealed trait Stack[A, B] extends (A => B) {
      override def compose[C](f: C => A): Stack[C, B] = Stack.Compose(f, this)
    }

    object Stack {
      final case class Base[A, B](f: A => B) extends Stack[A, B] {
        def apply(a: A) = f(a)
      }

      final case class Compose[E, A, B](f: A => E, tail: Stack[E, B]) extends Stack[A, B] {
        def apply(a: A) = {
          /*
          // this is what we wish we could write
          @tailrec
          def inner[X, Y](x: X, st: Stack[X, Y]): Y = st match {
            case Base(f) => f(x)
            case Compose(f, tail) => inner(f(x), tail)
          }

          inner(f(a), tail)
          // ...but it won't be tail recursive because scalac has a bug with tailrec and GADTs
          */

          var x: Any = a
          var st: Stack[Any, Any] = this.asInstanceOf[Stack[Any, Any]]

          while (st != null) {
            st match {
              case Base(f) =>
                x = f(x)
                st = null

              case Compose(f, tail) =>
                x = f(x)
                st = tail.asInstanceOf[Stack[Any, Any]]
            }
          }

          x.asInstanceOf[B]
        }
      }
    }
  }
  private final case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A] {
    override def map[B](g: A => B): Free[F,B] =
      Bind(r, (r: R) => f(r).map(g))
    override def flatMap[F2[x]>:F[x],B](g: A => Free[F2,B]): Free[F2,B] =
      Bind(r, (r: R) => f(r).flatMap(g))
    def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
      F2.flatMap(r.run(F2))(r => f(r).run(F2))
    def unroll_[G[+_]](implicit G: Functor[G], S: Sub1[F,G]): Trampoline[Unroll[A, G[Free[F,A]]]] = Sub1.substFree(r) match {
      case Pure(r) =>
        try Trampoline.suspend { f(r).unroll_ }
        catch { case NonFatal(err) => Trampoline.done { Unroll.Fail(err) } }
      case Fail(err) => Trampoline.done { Unroll.Fail(err) }
      case eval =>
        // NB: not bothering to convince Scala this is legit but since
        // `flatMap` and `map` build right-associated, and `Eval[F,A]` has type
        // Free[Attempt[A]], this is safe
        val ga: G[Any] = eval.asInstanceOf[Eval[G,Any]].fa
        val fr: Attempt[Any] => Free[F,A] = f.asInstanceOf[Attempt[Any] => Free[F,A]]
        Trampoline.done { Unroll.Eval(G.map(ga) { any => fr(Right(any)) }) }
    }
    def fold[F2[_],G[_],A2>:A](fold: Fold[F2,G,A2])(implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2] =
      fold.suspend { Sub1.substFree(r) match {
        case Pure(r) => fold.bind(r) { r => f(r).fold(fold) }
        case Fail(err) => fold.fail(err)
        // NB: Scala won't let us pattern match on Eval here, but this is safe since `flatMap` and `map`
        // prevent left-associated flatMaps
        case eval =>
          val fa: F2[Any] = eval.asInstanceOf[Eval[F2,Any]].fa
          val fr: Attempt[Any] => Free[F,A] = f.asInstanceOf[Attempt[Any] => Free[F,A]]
          fold.eval(fa)(a => fr(a).fold(fold))
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
