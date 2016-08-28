package fs2.util

/** Provides infix syntax for the typeclasses in the util package. */
object syntax {

  /** Infix syntax for functors. */
  implicit class FunctorOps[F[_],A](val self: F[A]) extends AnyVal {
    def map[B](f: A => B)(implicit F: Functor[F]): F[B] = F.map(self)(f)
    def as[B](b: B)(implicit F: Functor[F]): F[B] = F.map(self)(_ => b)
  }

  /** Infix syntax for applicatives. */
  implicit class ApplicativeOps[F[_],A](val self: F[A]) extends AnyVal {
    def ap[B](f: F[A => B])(implicit F: Applicative[F]): F[B] = F.ap(self)(f)
    def product[B](fb: F[B])(implicit F: Applicative[F]): F[(A, B)] =
      fb.ap(self.map(a => (b: B) => (a, b)))
    def *>[B](fb: F[B])(implicit F: Applicative[F]): F[B] =
      fb.ap(self.map(a => (b: B) => b))
    def <*[B](fb: F[B])(implicit F: Applicative[F]): F[A] =
      fb.ap(self.map(a => (b: B) => a))
  }

  /** Infix syntax for monads. */
  implicit class MonadOps[F[_],A](val self: F[A]) extends AnyVal {
    def flatMap[B](f: A => F[B])(implicit F: Monad[F]): F[B] = F.flatMap(self)(f)
    def flatten[B](implicit F: Monad[F], ev: A <:< F[B]): F[B] = F.flatMap(self)(a => ev(a))
    def andThen[A, B](b: F[B])(implicit F: Monad[F]): F[B] = F.flatMap(self)(_ => b)
    def >>[A, B](b: F[B])(implicit F: Monad[F]): F[B] = andThen(b)
  }

  /** Infix syntax for `traverse`. */
  implicit class TraverseOps[F[_],G[_],A](val self: F[A]) extends AnyVal {
    def traverse[B](f: A => G[B])(implicit F: Traverse[F], G: Applicative[G]): G[F[B]] = F.traverse(self)(f)
  }

  /** Infix syntax for `sequence`. */
  implicit class SequenceOps[F[_],G[_],A](val self: F[G[A]]) extends AnyVal {
    def sequence(implicit F: Traverse[F], G: Applicative[G]): G[F[A]] = F.sequence(self.asInstanceOf[F[G[A]]])
  }

  /** Infix syntax for catchables. */
  implicit class CatchableOps[F[_],A](val self: F[A]) extends AnyVal {
    def attempt(implicit F: Catchable[F]): F[Attempt[A]] = F.attempt(self)
  }

  /** Infix syntax for effects. */
  implicit class EffectOps[F[_],A](val self: F[A]) extends AnyVal {
    def unsafeRunAsync(cb: Attempt[A] => Unit)(implicit F: Effect[F]): Unit = F.unsafeRunAsync(self)(cb)
  }

  /** Infix syntax for asyncs. */
  implicit class AsyncOps[F[_],A](val self: F[A]) extends AnyVal {
    def start(implicit F: Async[F]): F[F[A]] = F.start(self)
  }

  /** Infix syntax for `parallelTraverse`. */
  implicit class ParallelTraverseOps[F[_],G[_],A](val self: F[A]) extends AnyVal {
    def parallelTraverse[B](f: A => G[B])(implicit F: Traverse[F], G: Async[G]): G[F[B]] = G.parallelTraverse(self)(f)
  }

  /** Infix syntax for `parallelSequence`. */
  implicit class ParallelSequenceOps[F[_],G[_],A](val self: F[G[A]]) extends AnyVal {
    def parallelSequence(implicit F: Traverse[F], G: Async[G]): G[F[A]] = G.parallelSequence(self.asInstanceOf[F[G[A]]])
  }
}
