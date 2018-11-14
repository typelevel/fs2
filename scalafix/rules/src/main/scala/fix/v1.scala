package fix

import scalafix.v1._
import scala.meta._
import scalafix.lint.LintSeverity
import fixUtils._

class v1 extends SemanticRule("v1") {
  override def fix(implicit doc: SemanticDocument): Patch =
    (StreamAppRules(doc.tree) ++ SchedulerRules(doc.tree) ++ BracketRules(doc.tree) ++ ConcurrentDataTypesRules(
      doc.tree)).asPatch
}

object fixUtils {

  // Maybe to improve, but not needed for our purposes
  def getTypeSymbol(symbol: Symbol)(implicit doc: SemanticDocument): Option[Symbol] =
    symbol.info.flatMap(_.signature match {
      case MethodSignature(_, _, returnType) =>
        getSymbol(returnType)
      case ValueSignature(t) => getSymbol(t)
      case _                 => None
    })

  def getSymbol(t: SemanticType): Option[Symbol] =
    t match {
      case t: TypeRef    => Some(t.symbol)
      case t: SingleType => Some(t.symbol)
      case t: ThisType   => Some(t.symbol)
      case t: SuperType  => Some(t.symbol)
      case _             => None
    }

  def getEffectType(symbol: Symbol)(implicit doc: SemanticDocument): String =
    getType(symbol).toString
      .takeWhile(_ != '[') // There might be a better way, but for our purposes it's enough

  // From https://scalacenter.github.io/scalafix/docs/developers/semantic-type.html
  def getType(symbol: Symbol)(implicit doc: SemanticDocument): SemanticType =
    symbol.info.map(_.signature match {
      case MethodSignature(_, _, returnType) =>
      returnType
    case _ =>
      NoType
    }
    ).getOrElse(NoType)

  def containsImport(importer: Importer)(implicit doc: SemanticDocument): Boolean =
    doc.tree
      .collect {
        case i: Importer if i == importer =>
          true
        case _ =>
          false
      }
      .exists(identity)
}

object BracketRules {

  def apply(t: Tree): List[Patch] =
    t.collect {
      case e: Enumerator.Generator => replaceBracket(e.rhs)
      case e: Enumerator.Val       => replaceBracket(e.rhs)
      case b: Term.Apply =>
        b.args.map(replaceBracket).asPatch
      case b: Term.Tuple =>
        b.args.map(replaceBracket).asPatch
      case b: Term.Block =>
        b.stats.collect {
          case s: Term.Apply => replaceBracket(s)
        }.asPatch
      case d: Defn.Val =>
        d.rhs match {
          case t: Term.Apply => replaceBracket(t)
          case _             => Patch.empty
        }
      case d: Defn.Def =>
        d.body match {
          case t: Term.Apply => replaceBracket(t)
          case _             => Patch.empty
        }
      case d: Defn.Var =>
        d.rhs match {
          case Some(t: Term.Apply) => replaceBracket(t)
          case s                   => Patch.empty
        }
    }

  def replaceBracket(p: Tree): Patch =
    p match {
      case b @ Term.Apply(
      Term.Apply(
      s @ Term.Select(_, Term.Name("bracket")),
      List(
      _
      )
      ),
      List(
      _,
      _
      )
      ) =>
        val newBracket = traverseBracket(b)
        Patch.replaceTree(b, newBracket.toString)
      case b => apply(b).asPatch
    }

  def traverseBracket(s: Stat): Stat =
    s match {
      case Term.Apply(Term.Apply(t @ Term.Select(_, Term.Name("bracket")), List(acquire)),
      List(use, release)) =>
        val newBracket =
          Term.Apply(Term.Select(Term.Apply(Term.Apply(t, List(acquire)), List(release)),
            Term.Name("flatMap")),
            List(traverseUse(use)))
        newBracket
      case t => t
    }

  def traverseUse(p: Term): Term =
    p match {
      case Term.Function(params, body) =>
        Term.Function(params, traverseBracket(body).asInstanceOf[Term])
    }
}


object ConcurrentDataTypesRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] =
    replaceSemaphore :: renameQueue :: renameTopic :: t.collect {
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

      // Signal
      case t @ immutableSignalMatcher(_: Term.Name) =>
        Patch.replaceTree(t, "fs2.concurrent.Signal")
      case immutableSignalMatcher(Type.Apply(s, _)) =>
        Patch.replaceTree(s, "fs2.concurrent.Signal")
      case mutableSignalMatcher(Term.Apply(s, _)) =>
        Patch.replaceTree(s, "fs2.concurrent.SignallingRef")
      case mutableSignalMatcher(Type.Apply(s, _)) =>
        Patch.replaceTree(s, "fs2.concurrent.SignallingRef")

    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  def isRef(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(refMatcher.matches)

  // This is doable because fs2.async.mutable.Semaphore and cats.effect.concurrent.Semaphore symbols have the same depth
  def replaceSemaphore(implicit doc: SemanticDocument) =
    Patch.replaceSymbols("fs2/async/mutable/Semaphore." -> "cats.effect.concurrent.Semaphore")

  def renameQueue(implicit doc: SemanticDocument) =
    Patch.renameSymbol(Symbol("fs2/async/mutable/Queue."), "fs2.concurrent.Queue")

  def renameTopic(implicit doc: SemanticDocument) =
    Patch.renameSymbol(Symbol("fs2/async/mutable/Topic."), "fs2.concurrent.Topic")

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
  val immutableSignalMatcher = SymbolMatcher.normalized("fs2/async/immutable/Signal.")
  val mutableSignalMatcher = SymbolMatcher.normalized("fs2/async/mutable/Signal.")
  val queueMatcher = SymbolMatcher.normalized("fs2/async/mutable/Queue.")

}


object SchedulerRules {

  def apply(t: Tree)(implicit doc: SemanticDocument): List[Patch] =
    t.collect {
      case t @ schedulerMatcher(_: Type.Name) =>
        Patch.replaceTree(t, timer(Type.Name("F")).toString()) //TODO: Use actual effect
      case Term.ApplyType(s, List(f)) if isScheduler(s) =>
        Patch.replaceTree(t, timer(f).toString())
      case sched @ Term.Apply(
      Term.ApplyType(Term.Select(Term.Select(s, Term.Name("effect")), Term.Name("sleep")), _),
      List(d)) if isScheduler(s) =>
        val timerSleep = Term.Apply(Term.Select(s, Term.Name("sleep")), List(d))
        Patch.replaceTree(sched, timerSleep.toString())
      case sched @ Term
      .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep")), List(f)), List(d))
        if isScheduler(s) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep")), List(f)),
            List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
      .Apply(Term.ApplyType(Term.Select(s, Term.Name("sleep_")), List(f)), List(d))
        if isScheduler(s) =>
        val stream =
          Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("sleep_")), List(f)),
            List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
      .Apply(Term.ApplyType(Term.Select(s, Term.Name("awakeEvery")), List(f)), List(d))
        if isScheduler(s) =>
        val stream = Term.Apply(
          Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("awakeEvery")), List(f)),
          List(d))
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term
      .Apply(Term.Select(s, Term.Name("retry")), params) if isScheduler(s) =>
        val stream = Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("retry")), params)
        Patch.replaceTree(sched, stream.toString())
      case sched @ Term.Apply(
      Term.Select(
      s,
      Term.Name("through")
      ),
      List(
      Term.Apply(
      Term.Select(_, debounce @ Term.Name("debounce")),
      d
      )
      )
      ) if isStream(s) =>
        val newStream = Term.Apply(Term.Select(s, debounce), d)
        Patch.replaceTree(sched, newStream.toString())
      case sched @ Term.Apply(
      Term.Select(Term.Select(s, Term.Name("effect")), Term.Name("delayCancellable")),
      List(fa, d)) if isScheduler(s) =>
        val concurrent = Term.Apply(
          Term.Select(
            Term.ApplyType(Term.Name("Concurrent"), List(Type.Name(getEffectType(fa.symbol)))),
            Term.Name("race")),
          List(fa, Term.Apply(Term.Select(s, Term.Name("sleep")), List(d)))
        )
        Patch.replaceTree(sched, concurrent.toString())
      case sched @ Term.Apply(Term.Select(s, Term.Name("delay")), List(stream, d))
        if isScheduler(s) && isStream(stream) =>
        val newStream = Term.Apply(Term.Select(stream, Term.Name("delayBy")), List(d))
        Patch.replaceTree(sched, newStream.toString())
    }

  def timer(f: Type) = Type.Apply(Type.Name("Timer"), List(f))

  def isScheduler(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(schedulerMatcher.matches)

  def isStream(s: Tree)(implicit doc: SemanticDocument): Boolean =
    getTypeSymbol(s.symbol).fold(false)(streamMatcher.matches)

  val schedulerMatcher = SymbolMatcher.normalized("fs2/Scheduler")
  val streamMatcher = SymbolMatcher.normalized("fs2/Stream")

}


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
      case d @ Defn.Def(_, Term.Name("stream"), _, _, tpe, body) =>
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
      importer"cats.implicits._"))
      Patch.empty
    else Patch.addGlobalImport(importer"cats.syntax.functor._")

  private[this] val exitCodeSuccessMatcher = SymbolMatcher.exact("fs2/StreamApp.ExitCode.Success.")

}
