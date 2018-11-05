package fix

import scalafix.lint.LintSeverity
import scalafix.v1._

import scala.meta._

object StreamAppRules {
  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] =
    t.collect {
      case d: Defn =>
        replaceStreamApp(d)
      case exitCodeSuccessMatcher(fs2ExitCode @ Name(_)) =>
        Patch.lint(
          Diagnostic("StreamAppExitCode",
                     message = "You can remove this",
                     position = fs2ExitCode.pos,
                     severity = LintSeverity.Warning))
      case i @ Importee.Name(Name("StreamApp")) =>
        Patch.removeImportee(i) + addCatsEffectImports + addCatsSyntaxImport
    }

  private[this] def replaceStreamApp(d: Defn)(implicit doc: SemanticDocument): Patch = d match {
    case c @ Defn.Class(_, _, _, _, tpl @ Template(_, is, _, _)) if is.exists {
          case streamAppInit(_) => true; case _ => false
        } =>
      val newApp = c.copy(
        templ = replaceClassTemplate(tpl)
      )
      Patch.replaceTree(c, newApp.toString()) ++ streamAppObjects(c).map(o =>
        Patch.replaceTree(o.templ, replaceObjectTemplate(o.templ).toString()))
    case o @ Defn.Object(_, _, tpl @ Template(_, is, _, _)) if is.exists {
          case streamAppInit(_) => true; case _ => false
        } =>
      val newApp = o.copy(
        templ = replaceTemplate(tpl)
      )
      Patch.replaceTree(o, newApp.toString())
    case _ => Patch.empty
  }

  object streamAppInit {
    def unapply(i: Init): Option[Init] = i match {
      case i @ Init(Type.Apply(Type.Name("StreamApp"), _), _, _) => Some(i)
      case _                                                     => None
    }
  }

  private[this] def streamAppObjects(c: Defn.Class)(
      implicit doc: SemanticDocument): List[Defn.Object] =
    doc.tree.collect {
      case o: Defn.Object =>
        o.templ.inits.flatMap {
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

  private[this] def addIOAppType(inits: List[Init]): List[Init] =
    inits :+ Init.apply(Type.Name("IOApp"), Name("IOApp"), List())

  private[this] def removeStreamAppType(inits: List[Init]): List[Init] =
    inits.filter { case streamAppInit(_) => false; case _ => true }

  private[this] def replaceTemplate(tpl: Template): Template =
    tpl.copy(
      stats = replaceStats(tpl.stats),
      inits = removeStreamAppType(addIOAppType(tpl.inits))
    )

  private[this] def replaceClassTemplate(tpl: Template): Template =
    tpl.copy(
      stats = replaceClassStats(tpl.stats),
      inits = removeStreamAppType(tpl.inits)
    )

  private[this] def replaceObjectTemplate(tpl: Template): Template =
    tpl.copy(stats = addProgramRun(tpl.stats), inits = addIOAppType(tpl.inits))

  private[this] def replaceStats(stats: List[Stat]): List[Stat] =
    stats.map {
      case d @ Defn.Def(_, _, _, _, tpe, body) =>
        val fName = tpe.flatMap(getFName).get
        val newDef = d.copy(
          name = Term.Name("run"),
          paramss = List(
            List(
              Term.Param(List(),
                         Name("args"),
                         Some(Type.Apply(Type.Name("List"), List(Type.Name("String")))),
                         None))),
          decltpe = Some(Type.Apply(Type.Name(fName), List(Type.Name("ExitCode")))),
          body = Term.Apply(
            Term.Select(Term.Select(Term.Select(body, Term.Name("compile")), Term.Name("drain")),
                        Term.Name("as")),
            List(Term.Select(Term.Name("ExitCode"), Term.Name("Success")))
          )
        )
        newDef
      case s => s
    }

  private[this] val params = List(
    Term.Param(List(),
               Name("args"),
               Some(Type.Apply(Type.Name("List"), List(Type.Name("String")))),
               None))

  private[this] def replaceClassStats(stats: List[Stat]): List[Stat] =
    stats.map {
      case d @ Defn.Def(_, _, _, _, tpe, body) =>
        val fName = tpe.flatMap(getFName).get
        val newDef = d.copy(
          name = Term.Name("program"),
          paramss = List(params),
          decltpe = Some(Type.Apply(Type.Name(fName), List(Type.Name("ExitCode")))),
          body = Term.Apply(
            Term.Select(Term.Select(Term.Select(body, Term.Name("compile")), Term.Name("drain")),
                        Term.Name("as")),
            List(Term.Select(Term.Name("ExitCode"), Term.Name("Success")))
          )
        )
        newDef
      case s => s
    }

  private[this] def addProgramRun(stats: List[Stat]): List[Stat] =
    Defn.Def(
      mods = List(),
      name = Term.Name("run"),
      tparams = List(),
      paramss = List(params),
      decltpe = Some(Type.Apply(Type.Name("IO"), List(Type.Name("ExitCode")))),
      body = Term.Apply(Term.Name("program"), params.map(p => Term.Name(p.name.value)))
    ) :: stats

  private[this] def getFName(t: Type): Option[String] = t match {
    case Type.Apply(_, f :: _) =>
      f match {
        case Type.Name(n) => Some(n)
        case _            => None
      }
    case _ =>
      None
  }

  private[this] def addCatsEffectImports(implicit doc: SemanticDocument): Patch =
    if (!containsImport(importer"cats.effect._")) // the other cases are handled directly by scalafix
      Patch.addGlobalImport(Symbol("cats/effect/IO.")) +
        Patch.addGlobalImport(Symbol("cats/effect/ExitCode.")) +
        Patch.addGlobalImport(Symbol("cats/effect/IOApp."))
    else Patch.empty

  private[this] def addCatsSyntaxImport(implicit doc: SemanticDocument): Patch =
    if (containsImport(importer"cats.syntax.functor._") || containsImport(
          importer"cats.syntax.functor._") || containsImport(importer"cats.implicits._"))
      Patch.empty
    else Patch.addGlobalImport(importer"cats.syntax.functor._")

  private[this] val exitCodeSuccessMatcher = SymbolMatcher.exact("fs2/StreamApp.ExitCode.Success.")

}
