import sbt._, Keys._

import sbtspiewak.SpiewakSonatypePlugin
import xerial.sbt.Sonatype.SonatypeKeys._

object Publish extends AutoPlugin {

  override def requires = SpiewakSonatypePlugin
  override def trigger = allRequirements

  override def projectSettings = Seq(sonatypeCredentialHost := "s01.oss.sonatype.org")
}
