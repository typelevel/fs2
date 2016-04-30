package fs2

import org.scalatest.{ Args, FreeSpec, Matchers, Status }
import org.scalatest.prop.GeneratorDrivenPropertyChecks

abstract class Fs2Spec extends FreeSpec with GeneratorDrivenPropertyChecks with Matchers with TestUtil {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  override def runTest(testName: String, args: Args): Status = {
    println("Starting " + testName)
    try super.runTest(testName, args)
    finally println("Finished " + testName)
  }
}
