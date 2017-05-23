package fs2

import scala.concurrent.ExecutionContext

import cats.~>
import cats.effect.Effect
import cats.implicits._

import fs2.internal.{ Algebra, Free }

sealed abstract class AsyncPull[F[_],A] { self =>

  protected def get: Free[F, A]

  protected def cancellableGet: Free[F, (Free[F, A], Free[F, Unit])]

  /** Converts this future to a pull, that when flat mapped, semantically blocks on the result of the future. */
  def pull: Pull[F,Nothing,A] = Pull.fromFree(get.translate[Algebra[F,Nothing,?]](new (F ~> Algebra[F,Nothing,?]) {
    def apply[X](fx: F[X]) = Algebra.Eval(fx)
  }))

  /** Converts this future to a stream, that when flat mapped, semantically blocks on the result of the future. */
  def stream: Stream[F,A] = Stream.fromFree(get.translate[Algebra[F,A,?]](new (F ~> Algebra[F,A,?]) {
    def apply[X](fx: F[X]) = Algebra.Eval(fx)
  }).flatMap(Algebra.output1))

  /** Returns a new future from this future by applying `f` with the completed value `A`. */
  def map[B](f: A => B): AsyncPull[F,B] = new AsyncPull[F,B] {
    def get = self.get.map(f)
    def cancellableGet = self.cancellableGet.map { case (a, cancelA) => (a.map(f), cancelA) }
  }

  /** Returns a new future that completes with the result of the first future that completes between this future and `b`. */
  def race[B](b: AsyncPull[F,B])(implicit F: Effect[F], ec: ExecutionContext): AsyncPull[F,Either[A,B]] = new AsyncPull[F, Either[A,B]] {
    def get = cancellableGet.flatMap(_._1)
    def cancellableGet = Free.Eval(for {
      ref <- async.ref[F,Either[A,B]]
      t0 <- self.cancellableGet.run
      (a, cancelA) = t0
      t1 <- b.cancellableGet.run
      (b, cancelB) = t1
      _ <- ref.setAsync(a.run.map(Left(_)))
      _ <- ref.setAsync(b.run.map(Right(_)))
    } yield {
      (Free.Eval(ref.get.flatMap {
        case Left(a) => cancelB.run.as(Left(a): Either[A, B])
        case Right(b) => cancelA.run.as(Right(b): Either[A, B])
      }), Free.Eval(cancelA.run >> cancelB.run))
    })
  }

  /** Like [[race]] but requires that the specified future has the same result type as this future. */
  def raceSame(b: AsyncPull[F,A])(implicit F: Effect[F], ec: ExecutionContext): AsyncPull[F, AsyncPull.RaceResult[A,AsyncPull[F,A]]] =
    self.race(b).map {
      case Left(a) => AsyncPull.RaceResult(a, b)
      case Right(a) => AsyncPull.RaceResult(a, self)
    }

  override def toString: String = "AsyncPull$" + ##
}

object AsyncPull {

  /** Result of [[AsyncPull#raceSame]]. */
  final case class RaceResult[+A,+B](winner: A, loser: B)

  /** Associates a value of type `A` with the `index`-th position of vector `v`. */
  final case class Focus[A,B](get: A, index: Int, v: Vector[B]) {
    /** Returns a new vector equal to `v` with the value at `index` replaced with `b`. */
    def replace(b: B): Vector[B] = v.patch(index, List(b), 1)

    /** Returns a new vector equal to `v` with the value at `index` removed. */
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  /** Lifts a pure value in to [[AsyncPull]]. */
  def pure[F[_],A](a: A): AsyncPull[F,A] = new AsyncPull[F,A] {
    def get = Free.Pure(a)
    def cancellableGet = Free.Pure((get, Free.Pure(())))
  }

  /** Returns an async pull that gets its value from reading the specified ref. */
  def readRef[F[_],A](r: async.Ref[F,A]): AsyncPull[F,A] =
    new AsyncPull[F,A] {
      def get = Free.Eval(r.get)
      def cancellableGet = Free.Eval(r.cancellableGet).map { case (get, cancel) => (Free.Eval(get), Free.Eval(cancel)) }
    }

  /**
   * Like [[readRef]] but reads a `Ref[F,Either[Throwable,A]]` instead of a `Ref[F,A]`. If a `Left(t)` is read,
   * the `get` action fails with `t`.
   */
  def readAttemptRef[F[_],A](r: async.Ref[F,Either[Throwable,A]]): AsyncPull[F,A] =
    new AsyncPull[F,A] {
      def get = Free.Eval(r.get).flatMap(_.fold(Free.Fail(_), Free.Pure(_)))
      def cancellableGet = Free.Eval(r.cancellableGet).map { case (get, cancel) =>
        (Free.Eval(get).flatMap(_.fold(Free.Fail(_), Free.Pure(_))), Free.Eval(cancel))
      }
    }
}
