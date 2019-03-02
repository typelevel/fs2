package fs2

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import cats.effect.{ContextShift, IO, Timer}

import org.typelevel.discipline.Laws
import org.scalatest.{ Args, Assertion, AsyncFreeSpec, FreeSpec, Matchers, Status, Succeeded, Suite }
import org.scalatest.concurrent.{ AsyncTimeLimitedTests, TimeLimitedTests }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.Span

abstract class Fs2Spec extends AsyncFreeSpec with AsyncTimeLimitedTests with GeneratorDrivenPropertyChecks with Matchers with ChunkGenerators {
  implicit val timeout: FiniteDuration = 60.seconds
  val timeLimit: Span = timeout

  implicit override val executionContext: ExecutionContext = ExecutionContext.Implicits.global

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
}

  // implicit def futureUnitToFutureAssertion(fu: Future[Unit]): Future[Assertion] = fu.map(_ => Succeeded)