package fs2

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import cats.effect.{ContextShift, IO, Timer}

import org.typelevel.discipline.Laws
import org.scalatest.{ Args, Assertion, AsyncFreeSpec, FreeSpec, Matchers, Status, Succeeded, Suite }
import org.scalatest.concurrent.{ AsyncTimeLimitedTests, TimeLimitedTests }
import org.scalatestplus.scalacheck.{ Checkers, ScalaCheckDrivenPropertyChecks }
import org.scalatest.time.Span

abstract class Fs2Spec extends FreeSpec with Fs2SpecLike with TimeLimitedTests with Checkers {
  val timeLimit: Span = timeout

  def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for ((id, prop) ← ruleSet.all.properties)
      s"${name}.${id}" in check(prop)
}

abstract class AsyncFs2Spec extends AsyncFreeSpec with Fs2SpecLike with AsyncTimeLimitedTests {
  val timeLimit: Span = timeout
  implicit override val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  implicit def futureUnitToFutureAssertion(fu: Future[Unit]): Future[Assertion] = fu.map(_ => Succeeded)
}

trait Fs2SpecLike extends Suite
  with ScalaCheckDrivenPropertyChecks
  with Matchers {

  implicit val timeout: FiniteDuration = 60.seconds

  implicit val timerIO: Timer[IO] = IO.timer(TestUtil.executionContext)
  implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(TestUtil.executionContext)

  lazy val verbose: Boolean = sys.props.get("fs2.test.verbose").isDefined

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    if (verbose) println("Starting " + testName)
    try super.runTest(testName, args)
    finally if (verbose) println("Finished " + testName)
  }
}
