package fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._

object StreamAppRules {
  def unapply(t: Tree)(implicit doc: SemanticDocument): Option[Patch] =
    t match {
      case Defn.Class(_, _, _, _, tpl) =>
        Some(replaceStreamApp(tpl) + replaceStreamMethod(tpl))
      case Defn.Object(_, _, tpl) =>
        Some(replaceStreamApp(tpl) + replaceStreamMethod(tpl))
      case exitCodeSuccessMatcher(fs2ExitCode @ Name(_)) =>
        Some(Patch.lint(Diagnostic("StreamAppExitCode", message = "You can remove this", position = fs2ExitCode.pos, severity = LintSeverity.Warning)))
      case i@Importee.Name(Name("StreamApp")) =>
        Some(Patch.removeImportee(i) + addCatsEffectImports + addCatsSyntaxImport)
      case _ =>
        None
    }

  private[this] def replaceStreamApp(tpl: Template): Patch =
    tpl.inits.collect{
      case s@Init(Type.Apply(Type.Name("StreamApp"), List(Type.Name("IO"))), _, _) =>
        Patch.replaceTree(s, "IOApp")
    }.asPatch

  private[this] def replaceStreamMethod(tpl: Template)(implicit doc: SemanticDocument): Patch =
    tpl.stats.collect{
      case d@Defn.Def(_, _, _, _, _, body) =>
        val newDef = d.copy(
          name = Term.Name("run"),
          paramss = List(List(Term.Param(List(), Name("args"), Some(Type.Apply(Type.Name("List"), List(Type.Name("String")))), None))),
          decltpe = Some(Type.Apply(Type.Name("IO"), List(Type.Name("ExitCode")))),
          body = Term.Apply(Term.Select(Term.Select(Term.Select(body, Term.Name("compile")), Term.Name("drain")), Term.Name("as")), List(Term.Select(Term.Name("ExitCode"), Term.Name("Success"))))
        )
      Patch.replaceTree(d, newDef.toString)
    }.asPatch

  private[this] def addCatsEffectImports(implicit c: SemanticContext): Patch =
    Patch.addGlobalImport(Symbol("cats/effect/IO.")) +
      Patch.addGlobalImport(Symbol("cats/effect/ExitCode.")) +
        Patch.addGlobalImport(Symbol("cats/effect/IOApp."))

  private[this] def addCatsSyntaxImport(implicit c: SemanticDocument): Patch =
    Patch.addGlobalImport(importer"cats.syntax.functor._")

  private[this] val exitCodeSuccessMatcher = SymbolMatcher.exact("fs2/StreamApp.ExitCode.Success.")

}
