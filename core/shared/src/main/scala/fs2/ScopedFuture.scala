package fs2

import scala.concurrent.ExecutionContext

import cats.{Applicative,Functor}
import cats.effect.Effect
import cats.implicits._

import fs2.util.Concurrent

/**
 * A future that evaluates to a value of type `A` and a `Scope[F,Unit]`.
 *
 * To use a `ScopedFuture`, convert to a `Pull` (via `f.pull`) or a `Stream` (via `f.stream`).
 */
sealed trait ScopedFuture[F[_],A] { self =>

  private[fs2] def get: F[(A, Scope[F,Unit])]

  private[fs2] def cancellableGet: F[(F[(A, Scope[F,Unit])], F[Unit])]

  private[fs2] def appendOnForce(p: Scope[F,Unit])(implicit F: Functor[F]): ScopedFuture[F,A] = new ScopedFuture[F,A] {
    def get = self.get.map { case (a,s0) => (a, s0 flatMap { _ => p }) }
    def cancellableGet =
      self.cancellableGet.map { case (f,cancel) =>
        (f.map { case (a,s0) => (a, s0 flatMap { _ => p }) }, cancel)
      }
  }

  /** Converts this future to a pull, that when flat mapped, semantically blocks on the result of the future. */
  def pull: Pull[F,Nothing,A] = Pull.eval(get) flatMap { case (a,onForce) => Pull.evalScope(onForce as a) }

  /** Converts this future to a stream, that when flat mapped, semantically blocks on the result of the future. */
  def stream: Stream[F,A] = Stream.eval(get) flatMap { case (a,onForce) => Stream.evalScope(onForce as a) }

  /** Returns a new future from this future by applying `f` with the completed value `A`. */
  def map[B](f: A => B)(implicit F: Functor[F]): ScopedFuture[F,B] = new ScopedFuture[F,B] {
    def get = self.get.map { case (a,onForce) => (f(a), onForce) }
    def cancellableGet = self.cancellableGet.map { case (a,cancelA) =>
      (a.map { case (a,onForce) => (f(a),onForce) }, cancelA)
    }
  }

  /** Returns a new future that completes with the result of the first future that completes between this future and `b`. */
  def race[B](b: ScopedFuture[F,B])(implicit F: Effect[F], ec: ExecutionContext): ScopedFuture[F,Either[A,B]] = new ScopedFuture[F, Either[A,B]] {
    def get = cancellableGet.flatMap(_._1)
    def cancellableGet = for {
      ref <- Concurrent.ref[F,Either[(A,Scope[F,Unit]),(B,Scope[F,Unit])]]
      t0 <- self.cancellableGet
      (a, cancelA) = t0
      t1 <- b.cancellableGet
      (b, cancelB) = t1
      _ <- ref.setAsync(a.map(Left(_)))
      _ <- ref.setAsync(b.map(Right(_)))
    } yield {
      (ref.get.flatMap {
        case Left((a,onForce)) => cancelB.as((Left(a),onForce))
        case Right((b,onForce)) => cancelA.as((Right(b),onForce))
      }, cancelA >> cancelB)
    }
  }

  /** Like [[race]] but requires that the specified future has the same result type as this future. */
  def raceSame(b: ScopedFuture[F,A])(implicit F: Effect[F], ec: ExecutionContext): ScopedFuture[F, ScopedFuture.RaceResult[A,ScopedFuture[F,A]]] =
    self.race(b).map {
      case Left(a) => ScopedFuture.RaceResult(a, b)
      case Right(a) => ScopedFuture.RaceResult(a, self)
    }
}

object ScopedFuture {

  /** Result of [[ScopedFuture#raceSame]]. */
  final case class RaceResult[+A,+B](winner: A, loser: B)

  /** Associates a value of type `A` with the `index`-th position of vector `v`. */
  final case class Focus[A,B](get: A, index: Int, v: Vector[B]) {
    /** Returns a new vector equal to `v` with the value at `index` replaced with `b`. */
    def replace(b: B): Vector[B] = v.patch(index, List(b), 1)

    /** Returns a new vector equal to `v` with the value at `index` removed. */
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  /** Lifts a pure value in to [[ScopedFuture]]. */
  def pure[F[_],A](a: A)(implicit F: Applicative[F]): ScopedFuture[F,A] = new ScopedFuture[F,A] {
    def get = F.pure(a -> Scope.pure(()))
    def cancellableGet = F.pure((get, F.pure(())))
  }

  /** Returns a future that gets its value from reading the specified ref. */
  def readRef[F[_],A](r: Concurrent.Ref[F,A])(implicit F: Functor[F]): ScopedFuture[F,A] =
    new ScopedFuture[F,A] {
      def get = r.get.map((_,Scope.pure(())))
      def cancellableGet = r.cancellableGet.map { case (f,cancel) =>
        (f.map((_,Scope.pure(()))), cancel)
      }
    }

  /** Races the specified collection of futures, returning the value of the first that completes. */
  def race[F[_],A](es: Vector[ScopedFuture[F,A]])(implicit F: Effect[F], ec: ExecutionContext): ScopedFuture[F,Focus[A,ScopedFuture[F,A]]] =
    indexedRace(es) map { case (a, i) => Focus(a, i, es) }

  private[fs2] def indexedRace[F[_],A](es: Vector[ScopedFuture[F,A]])(implicit F: Effect[F], ec: ExecutionContext): ScopedFuture[F,(A,Int)]
    = new ScopedFuture[F,(A,Int)] {
      def cancellableGet =
        Concurrent.ref[F,((A,Scope[F,Unit]),Int)].flatMap { ref =>
          val cancels: F[Vector[(F[Unit],Int)]] = (es zip (0 until es.size)).traverse { case (a,i) =>
            a.cancellableGet.flatMap { case (a, cancelA) =>
              ref.setAsync(a.map((_,i))).as((cancelA,i))
            }
          }
          cancels.flatMap { cancels =>
            F.pure {
              val get = ref.get.flatMap { case (a,i) =>
                cancels.collect { case (a,j) if j != i => a }.sequence.as((a,i))
              }
              val cancel = cancels.traverse(_._1).as(())
              (get.map { case ((a,onForce),i) => ((a,i),onForce) }, cancel)
            }
          }
        }
      def get = cancellableGet.flatMap(_._1)
    }
}
