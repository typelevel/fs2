package fs2.util

/** Provides infix syntax for the typeclasses in the util package. */
object syntax {

  implicit class FunctorOps[F[_],A](val self: F[A]) extends AnyVal {
    def map[B](f: A => B)(implicit F: Functor[F]): F[B] = F.map(self)(f)
    def as[B](b: B)(implicit F: Functor[F]): F[B] = F.map(self)(_ => b)
  }

  implicit class MonadOps[F[_],A](val self: F[A]) extends AnyVal {
    def flatMap[B](f: A => F[B])(implicit F: Monad[F]): F[B] = F.flatMap(self)(f)
    def flatten[B](implicit F: Monad[F], ev: A <:< F[B]): F[B] = F.flatMap(self)(a => ev(a))
    def andThen[A, B](b: F[B])(implicit F: Monad[F]): F[B] = F.flatMap(self)(_ => b)
    def >>[A, B](b: F[B])(implicit F: Monad[F]): F[B] = andThen(b)
  }

  implicit class TraverseOps[F[_],A](val self: Seq[A]) extends AnyVal {
    def traverse[B](f: A => F[B])(implicit F: Monad[F]): F[Vector[B]] = F.traverse(self)(f)
    def sequence[B](implicit F: Monad[F], ev: A =:= F[B]): F[Vector[B]] = F.sequence(self.asInstanceOf[Seq[F[B]]])
  }

  implicit class CatchableOps[F[_],A](val self: F[A]) extends AnyVal {
    def attempt(implicit F: Catchable[F]): F[Attempt[A]] = F.attempt(self)
  }

  implicit class EffectOps[F[_],A](val self: F[A]) extends AnyVal {
    def unsafeRunAsync(cb: Attempt[A] => Unit)(implicit F: Effect[F]): Unit = F.unsafeRunAsync(self)(cb)
  }

  implicit class AsyncOps[F[_],A](val self: F[A]) extends AnyVal {
    def start(implicit F: Async[F]): F[F[A]] = F.start(self)
  }

  implicit class ParallelTraverseOps[F[_],A](val self: Seq[A]) extends AnyVal {
    def parallelTraverse[B](f: A => F[B])(implicit F: Async[F]): F[Vector[B]] = F.parallelTraverse(self)(f)
    def parallelSequence[B](implicit F: Async[F], ev: A =:= F[B]): F[Vector[B]] = F.parallelSequence(self.asInstanceOf[Seq[F[B]]])
  }
}
