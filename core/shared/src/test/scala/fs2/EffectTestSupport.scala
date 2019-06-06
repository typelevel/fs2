package fs2

import cats.~>
import cats.effect._
import cats.implicits._
import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.{Assertion, Succeeded}

trait EffectTestSupportLowPriority {
  protected def executionContext: ExecutionContext

  implicit def propCheckerAssertingSyncIO: ToFuturePropCheckerAsserting[SyncIO] =
    new ToFuturePropCheckerAsserting(new (SyncIO ~> Future) {
      def apply[X](io: SyncIO[X]): Future[X] = Future(io.unsafeRunSync)(executionContext)
    })(executionContext)
}

trait EffectTestSupport extends EffectTestSupportLowPriority {
  implicit def syncIoToFutureAssertion(io: SyncIO[Assertion]): Future[Assertion] =
    io.toIO.unsafeToFuture
  implicit def ioToFutureAssertion(io: IO[Assertion]): Future[Assertion] =
    io.unsafeToFuture
  implicit def syncIoUnitToFutureAssertion(io: SyncIO[Unit]): Future[Assertion] =
    io.toIO.as(Succeeded).unsafeToFuture
  implicit def ioUnitToFutureAssertion(io: IO[Unit]): Future[Assertion] =
    io.as(Succeeded).unsafeToFuture

  implicit def propCheckerAssertingIO: ToFuturePropCheckerAsserting[IO] =
    new ToFuturePropCheckerAsserting(new (IO ~> Future) {
      def apply[X](io: IO[X]): Future[X] = io.unsafeToFuture
    })(executionContext)
}
