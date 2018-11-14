package fix

import scalafix.v1._

class Fs2v010Tov1 extends SemanticRule("Fs2v010Tov1") {

  override def fix(implicit doc: SemanticDocument): Patch =
    (StreamAppRules(doc.tree) ++ SchedulerRules(doc.tree) ++ BracketRules(doc.tree) ++ ConcurrentDataTypesRules(
      doc.tree)).asPatch
}
