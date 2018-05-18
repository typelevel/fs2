package fs2

import cats._
import cats.implicits._
import cats.effect._

import scala.collection.generic.CanBuildFrom

trait Fold[F[_], O, R] { self =>
  def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)]

  def flatMap[R2, O2 >: O](f: R => Fold[F, O2, R2]): Fold[F, O2, R2] = new Fold[F, O2, R2] {
    def fold[S](initial: S)(g: (S, O2) => S)(implicit F: Sync[F]): F[(S, R2)] =
      self.fold(initial)(g).flatMap { case (s, r) => f(r).fold(s)(g) }
  }

  final def >>[O2 >: O, R2 >: R](that: => Fold[F, O2, R2]): Fold[F, O2, R2] =
    flatMap(_ => that)

  final def to[C[_]](implicit F: Sync[F], cbf: CanBuildFrom[Nothing, O, C[O]]): F[C[O]] =
    F.suspend(F.map(self.fold(cbf())(_ += _))(_._1.result))
}

object Fold {
  def pure[F[_], O, R](r: R): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      (initial, r).pure[F]
  }

  def output1[F[_], O](o: O): Fold[F, O, Unit] = new Fold[F, O, Unit] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, Unit)] =
      (f(initial, o), ()).pure[F]
  }

  def eval[F[_], O, R](fr: F[R]): Fold[F, O, R] = new Fold[F, O, R] {
    def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
      fr.map(r => (initial, r))
  }

  def output1Eval[F[_], O](fo: F[O]): Fold[F, O, Unit] =
    eval(fo).flatMap(o => output1[F, O](o))

  def bracket[F[_], A, O, R](acquire: F[A])(use: A => Fold[F, O, R],
                                            release: A => F[Unit]): Fold[F, O, R] =
    new Fold[F, O, R] {
      def fold[S](initial: S)(f: (S, O) => S)(implicit F: Sync[F]): F[(S, R)] =
        F.bracket(acquire)(a => use(a).fold(initial)(f))(a => release(a))
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

object FoldUsage {
  val p1 = Fold.output1(42) >> Fold.output1Eval(IO(1))
  val p2 =
    Fold.bracket(IO(println("acquired")))(_ => Fold.output1(42), _ => IO(println("released")))
}
