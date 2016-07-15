package fs2

import org.scalatest.{ Args, FreeSpec, Matchers, Status }
import org.scalatest.concurrent.{ Eventually, TimeLimitedTests }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.SpanSugar._

abstract class Fs2Spec extends FreeSpec
  with GeneratorDrivenPropertyChecks
  with Matchers
  with TimeLimitedTests
  with Eventually
  with TestUtil {

  val timeLimit = 120.seconds

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = timeLimit)

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    println("Starting " + testName)
    try super.runTest(testName, args)
    finally println("Finished " + testName)
  }
}
