package fs2

import munit.ScalaCheckSuite
import cats.effect.laws.util.TestContext

abstract class Fs2Suite extends ScalaCheckSuite with TestPlatform with Generators {

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 25 else 5)
      .withWorkers(1)

  val testContext = FunFixture[TestContext](setup = _ => TestContext(), teardown = _ => ())
}
