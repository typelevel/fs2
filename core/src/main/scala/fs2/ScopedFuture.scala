package fs2

import fs2.util.{Functor,Monad}
import fs2.util.syntax._

/**
 * Future that evaluates to a value of type `A` and a `Scope[F,Unit]`.
 *
 * To use a `Future`, convert to a `Pull` (via `f.pull`) or a `Stream` (via `f.stream`).
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

  def pull: Pull[F,Nothing,A] = Pull.eval(get) flatMap { case (a,onForce) => Pull.evalScope(onForce as a) }

  def stream: Stream[F,A] = Stream.eval(get) flatMap { case (a,onForce) => Stream.evalScope(onForce as a) }

  def map[B](f: A => B)(implicit F: Async[F]): ScopedFuture[F,B] = new ScopedFuture[F,B] {
    def get = self.get.map { case (a,onForce) => (f(a), onForce) }
    def cancellableGet = self.cancellableGet.map { case (a,cancelA) =>
      (a.map { case (a,onForce) => (f(a),onForce) }, cancelA)
    }
  }

  def race[B](b: ScopedFuture[F,B])(implicit F: Async[F]): ScopedFuture[F,Either[A,B]] = new ScopedFuture[F, Either[A,B]] {
    def get = cancellableGet.flatMap(_._1)
    def cancellableGet = for {
      ref <- F.ref[Either[(A,Scope[F,Unit]),(B,Scope[F,Unit])]]
      t0 <- self.cancellableGet
      (a, cancelA) = t0
      t1 <- b.cancellableGet
      (b, cancelB) = t1
      _ <- ref.set(a.map(Left(_)))
      _ <- ref.set(b.map(Right(_)))
    } yield {
      (ref.get.flatMap {
        case Left((a,onForce)) => cancelB.as((Left(a),onForce))
        case Right((b,onForce)) => cancelA.as((Right(b),onForce))
      }, cancelA >> cancelB)
    }
  }

  def raceSame(b: ScopedFuture[F,A])(implicit F: Async[F]): ScopedFuture[F, ScopedFuture.RaceResult[A,ScopedFuture[F,A]]] =
    self.race(b).map {
      case Left(a) => ScopedFuture.RaceResult(a, b)
      case Right(a) => ScopedFuture.RaceResult(a, self)
    }
}

object ScopedFuture {

  case class RaceResult[+A,+B](winner: A, loser: B)

  case class Focus[A,B](get: A, index: Int, v: Vector[B]) {
    def replace(b: B): Vector[B] = v.patch(index, List(b), 1)
    def delete: Vector[B] = v.patch(index, List(), 1)
  }

  def pure[F[_],A](a: A)(implicit F: Monad[F]): ScopedFuture[F,A] = new ScopedFuture[F,A] {
    def get = F.pure(a -> Scope.pure(()))
    def cancellableGet = F.pure((get, F.pure(())))
  }

  def readRef[F[_],A](r: Async.Ref[F,A])(implicit F: Async[F]): ScopedFuture[F,A] =
    new ScopedFuture[F,A] {
      def get = r.get.map((_,Scope.pure(())))
      def cancellableGet = r.cancellableGet.map { case (f,cancel) =>
        (f.map((_,Scope.pure(()))), cancel)
      }
    }

  def race[F[_]:Async,A](es: Vector[ScopedFuture[F,A]]): ScopedFuture[F,Focus[A,ScopedFuture[F,A]]] =
    indexedRace(es) map { case (a, i) => Focus(a, i, es) }

  private[fs2] def indexedRace[F[_],A](es: Vector[ScopedFuture[F,A]])(implicit F: Async[F]): ScopedFuture[F,(A,Int)]
    = new ScopedFuture[F,(A,Int)] {
      def cancellableGet =
        F.ref[((A,Scope[F,Unit]),Int)].flatMap { ref =>
          val cancels: F[Vector[(F[Unit],Int)]] = (es zip (0 until es.size)).traverse { case (a,i) =>
            a.cancellableGet.flatMap { case (a, cancelA) =>
              ref.set(a.map((_,i))).as((cancelA,i))
            }
          }
          cancels.flatMap { cancels =>
            F.pure {
              val get = ref.get.flatMap { case (a,i) =>
                F.sequence(cancels.collect { case (a,j) if j != i => a }).map(_ => (a,i))
              }
              val cancel = cancels.traverse(_._1).as(())
              (get.map { case ((a,onForce),i) => ((a,i),onForce) }, cancel)
            }
          }
        }
      def get = cancellableGet.flatMap(_._1)
    }
}
