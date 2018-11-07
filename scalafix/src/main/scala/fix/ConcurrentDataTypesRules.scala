package fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._

object ConcurrentDataTypesRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] = //TODO: return types
    t.collect {
      // fs2 Ref -> cats effect Ref
      case Term.Apply(refMatcher(n: Term.Name), _) =>
        Patch.replaceTree(n, "cats.effect.concurrent.Ref.of")
      case Type.Apply(t @ Type.Name("Ref"), _) =>
        Patch.replaceTree(t, "cats.effect.concurrent.Ref")
      case Term.Apply(Term.ApplyType(r @ refMatcher(_), _), _) =>
        Patch.replaceTree(r, "cats.effect.concurrent.Ref")
      case Term.Apply(r @ Term.Name("refOf"), _) =>
        Patch.replaceTree(r, "cats.effect.concurrent.Ref.of")
      case Term.Apply(Term.ApplyType(r @ Term.Name("refOf"), _), _) =>
        Patch.replaceTree(r, "cats.effect.concurrent.Ref.of")
      case setSyncMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.replaceTree(s, "set")
      case setAsyncMatcher(Term.Apply(t @ Term.Select(_, s), _)) =>
        Patch.lint(
          Diagnostic("Removed",
                     message = "This got removed. Consider revisiting the implementation",
                     position = s.pos,
                     severity = LintSeverity.Error))
      case modifyMatcher(Term.Apply(Term.Select(_, s), _)) =>
        Patch.replaceTree(s, "update")
      case modify2Matcher(Term.Apply(Term.Select(_, s), _)) =>
        Patch.replaceTree(s, "modify")
      case tryModifyMatcher(Term.Apply(Term.Select(_, s), _)) =>
        Patch.replaceTree(s, "tryUpdate")
      case tryModify2Matcher(Term.Apply(Term.Select(_, s), _)) =>
        Patch.replaceTree(s, "tryModify")

      // Promise -> Deferred
      case t @ Term.Select(promiseMatcher(_), Term.Name("empty")) =>
        Patch.replaceTree(t, "cats.effect.concurrent.Deferred")
      case promiseMatcher(t @ Type.Name("Promise")) =>
        Patch.replaceTree(t, s"cats.effect.concurrent.Deferred")
      case t @ promiseLowercaseMatcher(_) =>
        Patch.replaceTree(t, "cats.effect.concurrent.Deferred")
      case cancellableGetMatcher(Term.Select(_, s)) =>
        Patch.replaceTree(s, "get")
      case timedGetMatcher(s @ Term.Apply(Term.Select(pre, Term.Name("timedGet")), List(d, _))) =>
        Patch.replaceTree(s, s"${pre}.timeout($d)")
    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  def isRef(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(refMatcher.matches)

  val refMatcher = SymbolMatcher.normalized("fs2/async/Ref.")
  val setSyncMatcher = SymbolMatcher.normalized("fs2/async/Ref#setSync.")
  val setAsyncMatcher = SymbolMatcher.normalized("fs2/async/Ref#setAsync.")
  val modifyMatcher = SymbolMatcher.normalized("fs2/async/Ref#modify.")
  val modify2Matcher = SymbolMatcher.normalized("fs2/async/Ref#modify2.")
  val tryModifyMatcher = SymbolMatcher.normalized("fs2/async/Ref#tryModify.")
  val tryModify2Matcher = SymbolMatcher.normalized("fs2/async/Ref#tryModify2.")
  val promiseMatcher = SymbolMatcher.normalized("fs2/async/Promise.")
  val promiseLowercaseMatcher = SymbolMatcher.normalized("fs2/async/promise.")
  val cancellableGetMatcher = SymbolMatcher.normalized("fs2/async/Promise#cancellableGet.")
  val timedGetMatcher = SymbolMatcher.normalized("fs2/async/Promise#timedGet.")
}
