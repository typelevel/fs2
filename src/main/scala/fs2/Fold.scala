package fs2

import cats._
import cats.implicits._
import cats.effect._

trait Fold[F[_], O, R] { self =>
  def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)]
  def step: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]]

  def flatMap[R2, O2 >: O](f: R => Fold[F, O2, R2]): Fold[F, O2, R2] = new Fold[F, O2, R2] {
    def fold[S](initial: S)(g: (S, O2) => S)(implicit F: Sync[F]): F[(S, R2)] =
      self.fold(initial)(g).flatMap { case (s, r) => f(r).fold(s)(g) }
    def step: Fold[F, Nothing, Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])]] =
      self.step.flatMap[Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])], Nothing] {
        case Right((hd, tl)) =>
          Fold.pure[F, Nothing, Either[R2, (Segment[O2, Unit], Fold[F, O2, R2])]](
            Right((hd, tl.flatMap(f))))
        case Left(r) =>
          f(r).step
      }
    def translate[G[_]](g: F ~> G): Fold[G, O2, R2] =
      self.translate(g).flatMap(r => f(r).translate(g))
  }

  final def >>[O2 >: O, R2 >: R](that: => Fold[F, O2, R2]): Fold[F, O2, R2] =
    flatMap(_ => that)

  def translate[G[_]](f: F ~> G): Fold[G, O, R]
}

object Fold {
  def pure[F[_], O, R](r: R): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      (initial, r).pure[F]
    def step: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
      pure(Either.left[R, (Segment[O, Unit], Fold[F, O, R])](r))
    def translate[G[_]](f: F ~> G): Fold[G, O, R] = pure[G, O, R](r)
  }

  def output1[F[_], O](o: O): Fold[F, O, Unit] = new Fold[F, O, Unit] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, Unit)] =
      F.delay((f(initial, o), ()))
    def step: Fold[F, Nothing, Either[Unit, (Segment[O, Unit], Fold[F, O, Unit])]] =
      pure(
        Either
          .right[Unit, (Segment[O, Unit], Fold[F, O, Unit])](
            (Segment.singleton(o), pure[F, O, Unit](()))))
    def translate[G[_]](f: F ~> G): Fold[G, O, Unit] = output1(o)
  }

  def output[F[_], O](os: Segment[O, Unit]): Fold[F, O, Unit] = new Fold[F, O, Unit] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, Unit)] =
      F.delay((os.fold(initial)(f).force.run._2, ())) // TODO add cancelation boundary every so often
    def step: Fold[F, Nothing, Either[Unit, (Segment[O, Unit], Fold[F, O, Unit])]] =
      pure(Either.right[Unit, (Segment[O, Unit], Fold[F, O, Unit])]((os, pure[F, O, Unit](()))))
    def translate[G[_]](f: F ~> G): Fold[G, O, Unit] = output(os)
  }

  def eval[F[_], O, R](fr: F[R]): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      fr.map(r => (initial, r))
    def step: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
      eval[F, Nothing, R](fr).flatMap[Either[R, (Segment[O, Unit], Fold[F, O, R])], Nothing](r =>
        pure(Either.left[R, (Segment[O, Unit], Fold[F, O, R])](r)))
    def translate[G[_]](f: F ~> G): Fold[G, O, R] = eval(f(fr))
  }

  def bracket[F[_], A, O, R](acquire: F[A])(use: A => Fold[F, O, R],
                                            release: A => F[Unit]): Fold[F, O, R] =
    new Fold[F, O, R] {
      def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
        F.bracket(acquire)(a => use(a).fold(initial)(f))(a => release(a))
      def step: Fold[F, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]] =
        bracket[F, A, Nothing, Either[R, (Segment[O, Unit], Fold[F, O, R])]](acquire)(
          a => use(a).step,
          release)
      def translate[G[_]](f: F ~> G): Fold[G, O, R] =
        bracket[G, A, O, R](f(acquire))(a => use(a).translate(f), a => f(release(a)))
    }

  implicit def monadInstance[F[_], O]: Monad[Fold[F, O, ?]] =
    new Monad[Fold[F, O, ?]] {
      def pure[A](a: A): Fold[F, O, A] = Fold.pure[F, O, A](a)
      def flatMap[A, B](p: Fold[F, O, A])(f: A => Fold[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Fold[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Fold.pure[F, O, B](b)
        }
    }
}
