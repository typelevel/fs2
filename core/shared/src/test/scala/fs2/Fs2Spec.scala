package fs2

import scala.concurrent.ExecutionContext

import org.scalatest.{ Args, AsyncFreeSpec, FreeSpec, Matchers, Status, Suite }
import org.scalatest.concurrent.{ AsyncTimeLimitedTests, TimeLimitedTests }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.Span

abstract class Fs2Spec extends FreeSpec with Fs2SpecLike with TimeLimitedTests {
  val timeLimit: Span = timeout
}

abstract class AsyncFs2Spec extends AsyncFreeSpec with Fs2SpecLike with AsyncTimeLimitedTests {
  val timeLimit: Span = timeout
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
}

trait Fs2SpecLike extends Suite
  with GeneratorDrivenPropertyChecks
  with Matchers
  with TestUtil {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 4)

  override def runTest(testName: String, args: Args): Status = {
    println("Starting " + testName)
    try super.runTest(testName, args)
    finally println("Finished " + testName)
  }
}
