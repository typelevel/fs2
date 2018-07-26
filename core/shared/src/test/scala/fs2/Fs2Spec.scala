package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import org.typelevel.discipline.Laws
import org.scalatest.{ Args, AsyncFreeSpec, FreeSpec, Matchers, Status, Suite }
import org.scalatest.concurrent.{ AsyncTimeLimitedTests, TimeLimitedTests }
import org.scalatest.prop.{ Checkers, GeneratorDrivenPropertyChecks }
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
}

trait Fs2SpecLike extends Suite
  with GeneratorDrivenPropertyChecks
  with Matchers {

  implicit val timeout: FiniteDuration = 60.seconds

  lazy val verbose: Boolean = sys.props.get("fs2.test.verbose").isDefined

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    if (verbose) println("Starting " + testName)
    try super.runTest(testName, args)
    finally if (verbose) println("Finished " + testName)
  }
}
