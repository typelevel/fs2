package fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._

object ConcurrentRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] = //TODO: return types
    t.collect {
      case Term.Apply(r @ refMatcher(_), _) =>
        Patch.replaceTree(r, Term.Select(r, Term.Name("of")).toString())
      case Term.Apply(Term.ApplyType(r @ refMatcher(_), _), _) =>
        Patch.replaceTree(r, Term.Select(r, Term.Name("of")).toString())
      case Term.Apply(Term.ApplyType(r @ Term.Name("refOf"), _), _) =>
        Patch.replaceTree(r, Term.Select(Term.Name("Ref"), Term.Name("of")).toString())
      case setSyncMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, Term.Name("set").toString())
      case setAsyncMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.lint(
          Diagnostic("Removed",
                     message = "This got removed. Consider revisiting the implementation",
                     position = s.pos,
                     severity = LintSeverity.Error))
      case modifyMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, Term.Name("update").toString())
      case modify2Matcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, Term.Name("modify").toString())
      case tryModifyMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, Term.Name("tryUpdate").toString())
      case tryModify2Matcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, Term.Name("tryModify").toString())
    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  def isRef(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(refMatcher.matches)

  val refMatcher = SymbolMatcher.normalized("fs2/async/Ref")
  val setSyncMatcher = SymbolMatcher.normalized("fs2/async/Ref#setSync.")
  val setAsyncMatcher = SymbolMatcher.normalized("fs2/async/Ref#setAsync.")
  val modifyMatcher = SymbolMatcher.normalized("fs2/async/Ref#modify.")
  val modify2Matcher = SymbolMatcher.normalized("fs2/async/Ref#modify2.")
  val tryModifyMatcher = SymbolMatcher.normalized("fs2/async/Ref#tryModify.")
  val tryModify2Matcher = SymbolMatcher.normalized("fs2/async/Ref#tryModify2.")

}
