package fix

import scalafix.v1._
import scala.meta._

class Fs2v010Tov1 extends SemanticRule("Fs2v010Tov1") {

  override def fix(implicit doc: SemanticDocument): Patch = //TODO: Maybe do something nicer than this
    doc.tree.collect {
      case StreamAppRules(patch) => patch
    }.asPatch + doc.tree.collect {
      case BracketRules(patch) => patch
    }.asPatch + doc.tree.collect {
      case SchedulerRules(patch) => patch
    }.asPatch
}
