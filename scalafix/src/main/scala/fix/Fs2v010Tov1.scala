package fix

import scalafix.v1._
import scala.meta._

class Fs2v010Tov1 extends SemanticRule("Fs2v010Tov1") {

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case StreamAppRules(patch) => patch
      case BracketRules(patch)   => patch
    }.asPatch
}
