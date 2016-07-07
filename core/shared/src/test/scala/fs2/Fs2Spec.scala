package fs2

import org.scalatest.{ Args, AsyncFreeSpec, FreeSpec, Matchers, Status, Suite }
import org.scalatest.concurrent.{ AsyncTimeLimitedTests, TimeLimitedTests }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.SpanSugar._

abstract class Fs2Spec extends FreeSpec with Fs2SpecLike with TimeLimitedTests {
  val timeLimit = 90.seconds
}

abstract class AsyncFs2Spec extends AsyncFreeSpec with Fs2SpecLike with AsyncTimeLimitedTests {
  val timeLimit = 90.seconds
}

trait Fs2SpecLike extends Suite
  with GeneratorDrivenPropertyChecks
  with Matchers
  with TestUtil {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    println("Starting " + testName)
    try super.runTest(testName, args)
    finally println("Finished " + testName)
  }
}
