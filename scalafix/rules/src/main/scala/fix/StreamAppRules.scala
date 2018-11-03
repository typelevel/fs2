package fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._

object StreamAppRules {
  def unapply(t: Tree)(implicit doc: SemanticDocument): Option[Patch] =
    t match {
      case d: Defn =>
        Some(replaceStreamApp(d))
      case exitCodeSuccessMatcher(fs2ExitCode @ Name(_)) =>
        Some(Patch.lint(Diagnostic("StreamAppExitCode", message = "You can remove this", position = fs2ExitCode.pos, severity = LintSeverity.Warning)))
      case i@Importee.Name(Name("StreamApp")) =>
        Some(Patch.removeImportee(i) + addCatsEffectImports + addCatsSyntaxImport)
      case _ =>
        None
    }

  private[this] def replaceStreamApp(d: Defn)(implicit doc: SemanticDocument): Patch = d match {
    case c@Defn.Class(_, _, _, _, tpl@Template(_, is, _, _)) if is.exists{case streamAppInit(_) => true; case _ => false} =>
      val newApp = Defn.Object(c.mods, Term.Name(c.name.value), replaceTemplate(tpl))
      Patch.replaceTree(c, newApp.toString()) ++ streamAppObjects(c).map(o => Patch.replaceTree(o, ""))
    case o@Defn.Object(_, _, tpl@Template(_, is, _, _)) if is.exists{case streamAppInit(_) => true; case _ => false} =>
      val newApp = o.copy(
        templ = replaceTemplate(tpl)
      )
      Patch.replaceTree(o, newApp.toString())
    case _ => Patch.empty
  }

  object streamAppInit {
    def unapply(i: Init): Option[Init] = i match {
      case i@Init(Type.Apply(Type.Name("StreamApp"), _), _, _) => Some(i)
      case _ => None
    }
  }

  private[this] def streamAppObjects(c: Defn.Class)(implicit doc: SemanticDocument): List[Defn.Object] = doc.tree.collect{
    case o: Defn.Object =>
      o.templ.inits.flatMap{
        case Init(n, _, _) =>
          n match {
            case Type.Apply(t, _) if t.toString() == c.name.value =>
              Some(o)
            case i =>
              None
          }
        case i => None
      }
    case _ => List()
  }.flatten

  private[this] def replaceStreamAppType(inits: List[Init]): List[Init] =
    Init.apply(Type.Name("IOApp"), Name("IOApp"), List()) :: inits.filter{ case streamAppInit(_) => false; case _ => true}

  private[this] def replaceTemplate(tpl: Template)(implicit doc: SemanticDocument): Template =
    tpl.copy(
      stats = replaceStats(tpl.stats),
      inits = replaceStreamAppType(tpl.inits))

  private[this] def replaceStats(stats: List[Stat]): List[Stat] =
    stats.map{
      case d@Defn.Def(_, _, _, _, _, body) =>
        val newDef = d.copy(
          name = Term.Name("run"),
          paramss = List(List(Term.Param(List(), Name("args"), Some(Type.Apply(Type.Name("List"), List(Type.Name("String")))), None))),
          decltpe = Some(Type.Apply(Type.Name("IO"), List(Type.Name("ExitCode")))),
          body = Term.Apply(Term.Select(
            Term.Select(
              Term.Select(body, //TODO: replace F with IO
                Term.Name("compile")),
              Term.Name("drain")),
            Term.Name("as")),
            List(Term.Select(Term.Name("ExitCode"), Term.Name("Success"))))
        )
        newDef
      case s => s
    }

  private[this] def addCatsEffectImports(implicit c: SemanticContext): Patch =
    Patch.addGlobalImport(Symbol("cats/effect/IO.")) +
      Patch.addGlobalImport(Symbol("cats/effect/ExitCode.")) +
        Patch.addGlobalImport(Symbol("cats/effect/IOApp."))

  private[this] def addCatsSyntaxImport(implicit c: SemanticDocument): Patch =
    Patch.addGlobalImport(importer"cats.syntax.functor._")

  private[this] val exitCodeSuccessMatcher = SymbolMatcher.exact("fs2/StreamApp.ExitCode.Success.")

}
