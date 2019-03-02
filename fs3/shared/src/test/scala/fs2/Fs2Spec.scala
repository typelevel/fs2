package fs2

import scala.concurrent.Future
import scala.concurrent.duration._

import cats.effect.{ContextShift, IO, Sync, SyncIO, Timer}
import cats.implicits._

import org.typelevel.discipline.Laws
import org.scalatest.{Args, Assertion, AsyncFreeSpec, FreeSpec, Matchers, Status, Succeeded, Suite}
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.Span

abstract class Fs2Spec
    extends AsyncFreeSpec
    with AsyncTimeLimitedTests
    with GeneratorDrivenPropertyChecks
    with Matchers
    with ChunkGenerators
    with TestPlatform {

  implicit val timeout: FiniteDuration = 60.seconds
  val timeLimit: Span = timeout

  implicit val timerIO: Timer[IO] = IO.timer(executionContext)
  implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(executionContext)

  lazy val verbose: Boolean = sys.props.get("fs2.test.verbose").isDefined

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    if (verbose) println("Starting " + testName)
    try super.runTest(testName, args)
    finally if (verbose) println("Finished " + testName)
  }

  implicit class Asserting[F[_], A](private val self: F[A]) {
    def asserting(f: A => Assertion)(implicit F: Sync[F]): F[Assertion] =
      self.flatMap(a => F.delay(f(a)))
  }

  implicit def syncIoToFutureAssertion(io: SyncIO[Assertion]): Future[Assertion] =
    io.toIO.unsafeToFuture
  implicit def ioToFutureAssertion(io: IO[Assertion]): Future[Assertion] =
    io.unsafeToFuture
  implicit def syncIoUnitToFutureAssertion(io: SyncIO[Unit]): Future[Assertion] =
    io.toIO.as(Succeeded).unsafeToFuture
  implicit def ioUnitToFutureAssertion(io: IO[Unit]): Future[Assertion] =
    io.as(Succeeded).unsafeToFuture

  class Err extends RuntimeException
}

// implicit def futureUnitToFutureAssertion(fu: Future[Unit]): Future[Assertion] = fu.map(_ => Succeeded)
