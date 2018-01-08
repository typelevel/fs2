package fs2

import scala.concurrent.ExecutionContext

import cats.~>
import cats.effect.Effect
import cats.implicits._

import fs2.internal.{Algebra, FreeC}

/** Result of `unconsAsync`. Conceptually similar to a `Future`. Can be forced via `pull` or `stream`. */
sealed abstract class AsyncPull[F[_], A] { self =>

  protected def get: FreeC[F, A]

  protected def cancellableGet: FreeC[F, (FreeC[F, A], FreeC[F, Unit])]

  /** Converts to a pull, that when flat mapped, semantically blocks on the result. */
  def pull: Pull[F, Nothing, A] =
    Pull.fromFreeC(get.translate[Algebra[F, Nothing, ?]](new (F ~> Algebra[F, Nothing, ?]) {
      def apply[X](fx: F[X]) = Algebra.Eval(fx)
    }))

  /** Converts to a stream, that when flat mapped, semantically blocks on the result. */
  def stream: Stream[F, A] =
    Stream.fromFreeC(
      get
        .translate[Algebra[F, A, ?]](new (F ~> Algebra[F, A, ?]) {
          def apply[X](fx: F[X]) = Algebra.Eval(fx)
        })
        .flatMap(Algebra.output1))

  /** Returns a new async pull by applying `f` with the completed value `A`. */
  def map[B](f: A => B): AsyncPull[F, B] = new AsyncPull[F, B] {
    def get = self.get.map(f)
    def cancellableGet = self.cancellableGet.map {
      case (a, cancelA) => (a.map(f), cancelA)
    }
  }

  /** Returns a new async pull that completes with the result of the first async pull that completes between this and `b`. */
  def race[B](b: AsyncPull[F, B])(implicit F: Effect[F],
                                  ec: ExecutionContext): AsyncPull[F, Either[A, B]] =
    new AsyncPull[F, Either[A, B]] {
      def get = cancellableGet.flatMap(_._1)
      def cancellableGet =
        FreeC.Eval(
          for {
            promise <- async.promise[F, Either[Throwable, Either[A, B]]]
            t0 <- self.cancellableGet.run
            (a, cancelA) = t0
            t1 <- b.cancellableGet.run
            (b, cancelB) = t1
            fa = a.run.map(Left.apply).attempt
            fb = b.run.map(Right.apply).attempt
            _ <- async.fork(fa.flatMap(promise.complete) *> cancelB.run)
            _ <- async.fork(fb.flatMap(promise.complete) *> cancelA.run)
          } yield
            FreeC.Eval(promise.get.flatMap(F.fromEither)) -> FreeC.Eval(cancelA.run *> cancelB.run))
    }

  /** Like [[race]] but requires that the specified async pull has the same result type as this. */
  def raceSame(b: AsyncPull[F, A])(
      implicit F: Effect[F],
      ec: ExecutionContext): AsyncPull[F, AsyncPull.RaceResult[A, AsyncPull[F, A]]] =
    self.race(b).map {
      case Left(a)  => AsyncPull.RaceResult(a, b)
      case Right(a) => AsyncPull.RaceResult(a, self)
    }

  override def toString: String = "AsyncPull$" + ##
}

object AsyncPull {

  /** Result of [[AsyncPull#raceSame]]. */
  final case class RaceResult[+A, +B](winner: A, loser: B)

  /** Associates a value of type `A` with the `index`-th position of vector `v`. */
  final case class Focus[A, B](get: A, index: Int, v: Vector[B]) {

    /** Returns a new vector equal to `v` with the value at `index` replaced with `b`. */
    def replace(b: B): Vector[B] = v.patch(index, List(b), 1)

    /** Returns a new vector equal to `v` with the value at `index` removed. */
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  /** Lifts a pure value in to [[AsyncPull]]. */
  def pure[F[_], A](a: A): AsyncPull[F, A] = new AsyncPull[F, A] {
    def get = FreeC.Pure(a)
    def cancellableGet = FreeC.Pure((get, FreeC.Pure(())))
  }

  /** Returns an async pull that gets its value from reading the specified promise. */
  def readPromise[F[_], A](p: async.Promise[F, A]): AsyncPull[F, A] =
    new AsyncPull[F, A] {
      def get = FreeC.Eval(p.get)
      def cancellableGet = FreeC.Eval(p.cancellableGet).map {
        case (get, cancel) => (FreeC.Eval(get), FreeC.Eval(cancel))
      }
    }

  /**
    * Like [[readPromise]] but reads a `Promise[F,Either[Throwable,A]]` instead of a `Promise[F,A]`. If a `Left(t)` is read,
    * the `get` action fails with `t`.
    */
  def readAttemptPromise[F[_], A](p: async.Promise[F, Either[Throwable, A]]): AsyncPull[F, A] =
    new AsyncPull[F, A] {
      def get = FreeC.Eval(p.get).flatMap(_.fold(FreeC.Fail(_), FreeC.Pure(_)))
      def cancellableGet = FreeC.Eval(p.cancellableGet).map {
        case (get, cancel) =>
          (FreeC.Eval(get).flatMap(_.fold(FreeC.Fail(_), FreeC.Pure(_))), FreeC.Eval(cancel))
      }
    }
}
