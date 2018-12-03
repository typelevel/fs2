package fs2.io

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._

/** Extension of `Async` which provides the `asyncYield` operation. */
private[io] trait AsyncYield[F[_]] {

  /**
    * Like `Async.async` but execution is shifted back to the default
    * execution context after the execution of the async operation.
    */
  def asyncYield[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
}

private[io] object AsyncYield {

  implicit def fromAsyncAndContextShift[F[_]](implicit F: Async[F],
                                              cs: ContextShift[F]): AsyncYield[F] =
    new AsyncYield[F] {
      def asyncYield[A](k: (Either[Throwable, A] => Unit) => Unit): F[A] =
        F.async(k).guarantee(cs.shift)
    }

  implicit def fromConcurrentEffect[F[_]](implicit F: ConcurrentEffect[F]): AsyncYield[F] =
    new AsyncYield[F] {
      def asyncYield[A](k: (Either[Throwable, A] => Unit) => Unit): F[A] =
        Deferred[F, Either[Throwable, A]].flatMap { d =>
          val runner = F.async(k).start.flatMap(_.join).attempt.flatMap(d.complete)
          F.delay(runner.runAsync(_ => IO.unit).unsafeRunSync) >> d.get.rethrow
        }
    }

}
