package fs2

import org.scalatest.FreeSpec
import org.scalatest.check.Checkers
import org.typelevel.discipline.Laws

abstract class LawsSpec extends FreeSpec with Checkers {
  def checkAll(name: String, ruleSet: Laws#RuleSet): Unit =
    for ((id, prop) <- ruleSet.all.properties)
      s"${name}.${id}" in check(prop)
}
