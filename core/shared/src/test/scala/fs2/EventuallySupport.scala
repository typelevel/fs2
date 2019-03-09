package fs2

import org.scalatest.concurrent.Eventually

trait EventuallySupport extends Eventually { self: Fs2Spec =>
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = timeLimit)
}
