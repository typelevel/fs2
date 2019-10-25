package fix

import scalafix.v1._

import scala.meta.{Enumerator, _}
import fixUtils._

import scala.collection.mutable

class v2 extends SemanticRule("v2") {
  override def fix(implicit doc: SemanticDocument): Patch =
    (BlockingExecutionContextRules(doc.tree) ++ SocketGroupRule(doc.tree)).asPatch
}

object fixUtils {

  def firstType(tpe: SemanticType): Option[SemanticType] =
    tpe match {
      case TypeRef(_, _, args) =>
        Some(args.head)
      case _ =>
        None
    }

  def secondType(tpe: SemanticType): Option[SemanticType] =
    tpe match {
      case TypeRef(_, _, args) =>
        Some(args(1))
      case _ =>
        None
    }

  // From https://scalacenter.github.io/scalafix/docs/developers/semantic-type.html
  def getType(symbol: Symbol)(implicit doc: SemanticDocument): SemanticType =
    symbol.info.map(_.signature match {
      case MethodSignature(_, _, returnType) =>
      returnType
      case ValueSignature(t) => t
    case _ =>
      NoType
    }
    ).getOrElse(NoType)

  def containsImport(importer: Importer)(implicit doc: SemanticDocument): Boolean =
    doc.tree
      .collect {
        case i: Importer if i.importees.intersect(importer.importees) == importer.importees =>
          true
        case _ =>
          false
      }
      .exists(identity)
}

object BlockingExecutionContextRules {

  def apply(tree: Tree)(implicit doc: SemanticDocument): List[Patch] = {

    val resourceSet = mutable.Set.empty[Position]
    val resourceFMap = mutable.Set.empty[Position]
    tree.collect {
      case sr @ Term.Apply(
                  Term.Select(
                    ap @ Term.Apply(
                          Term.Select(Term.Name("Stream"), Term.Name("resource")),
                          List(bec)
                    ),
                    Term.Name("flatMap") | Term.Name("map")
                  ),
                _) if isBlockingExecutionContext(bec) =>
        resourceFMap += sr.pos
        resourceSet += ap.pos
        val tpe = firstType(getType(bec.symbol)).get
        Patch.replaceTree(bec, s"Blocker[$tpe]") + addCatsEffectImports

      case sr @ Term.Apply(
                  Term.Select(
                    ap @ Term.Apply(
                            Term.ApplyType(
                              Term.Select(Term.Name("Stream"), Term.Name("resource")),
                              List(_, tpe2)
                            ),
                            List(bec)
                        ),
                      Term.Name("flatMap")| Term.Name("map")
                  ),
            _) if isBlockingExecutionContext(bec) =>
        resourceFMap += sr.pos
        resourceSet += ap.pos
        val tpe = firstType(getType(bec.symbol)).get

          List(Patch.replaceTree(tpe2, "Blocker"),
          Patch.replaceTree(bec, s"Blocker[$tpe]")).asPatch + addCatsEffectImports


      case fy: Term.ForYield if hasStreamResource(fy.enums) =>
        replaceBlockingExecutionContextForYield(fy.enums, resourceSet, resourceFMap)

      case fy: Term.ForYield if hasStreamResourceWithType(fy.enums) =>
        replaceBlockingExecutionContextWithTypeForYield(fy.enums, resourceSet, resourceFMap)

      case resourceMatcher(ap @ Term.Apply(_, List(bec))) if isBlockingExecutionContext(bec) =>
        if (resourceSet.contains(ap.pos))
          Patch.empty
        else {
            val tpe =
              bec match {
                case Term.ApplyType(_, List(x)) => x
                case _ => firstType(getType(bec.symbol)).get
              }
            Patch.replaceTree(bec, s"Blocker[$tpe]") + addCatsEffectImports
         }
      case linesAsyncMatcher(ap @ Term.Apply(_, List(_, bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case showLinesAsyncMatcher(ap @ Term.Apply(_, List(_, bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case showLinesStdOutAsyncMatcher(ap @ Term.Apply(_, List(bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case defaultMatcher(ap: Term.ApplyType) =>
        addBlockerTerm(ap)

      case readAllMatcher(ap @ Term.Apply(_,  List(_, bec, _))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case writeAllMatcher(ap @ Term.Apply(_,  List(_, bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case watcherMatcher(ap:Term.ApplyType) =>
        addBlockerTerm(ap)

      case watchMatcher(ap @ Term.Apply(fun, params)) =>
        val newParams =
          Term.Apply(
            Term.Select(Term.Name("Blocker"), Term.Name("liftExecutionContext")),
            List(
              Term.Apply(
                Term.Select(Term.Name("ExecutionContext"), Term.Name("fromExecutorService")),
                  List(
                    Term.Apply(
                      Term.Select(Term.Name("Executors"), Term.Name("newCachedThreadPool")),
                      List()
                    )
                  )
              )
            )
          ):: params
        Patch.replaceTree(ap, Term.Apply(fun, newParams).toString)

      case fromPathMatcher(ap @ Term.Apply(fun, List(_, bec, _))) =>
        replaceBlockingExecutionContextTermAndRenameFunction(bec, ap, resourceFMap)(fun, s"io.file.FileHandle.fromPath")


      case fromFileChannelMatcher(ap @ Term.Apply(fun, List(_, bec))) =>
        replaceBlockingExecutionContextTermAndRenameFunction(bec, ap, resourceFMap)(fun, "io.file.FileHandle.fromFileChannel")

      case readInputStreamMatcher(ap @ Term.Apply(_, params)) =>
        val bec = params(2)
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case unsafeReadInputStreamMatcher(ap @ Term.Apply(_, params)) =>
        val bec = params(2)
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case readRangeMatcher(ap @ Term.Apply(_, List(_, bec,_,_,_))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case stdinMatcher(ap @ Term.Apply(_, List(_, bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case stdoutMatcher(ap @ Term.Apply(_, List(bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case stdoutLinesMatcher(ap @ Term.Apply(_, params)) =>
        val bec = params.head
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)

      case stdinUtf8Matcher(ap @ Term.Apply(_, List(_, bec))) =>
        replaceBlockingExecutionContextTerm(bec, ap, resourceFMap)
    }

  }

  private def hasStreamResource(forEnumerators: Seq[Enumerator])(implicit doc: SemanticDocument): Boolean = {
    forEnumerators.exists {
       case Enumerator.Generator(_, Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("resource")), List(bec))) if isBlockingExecutionContext(bec) => true
       case _ => false
    }
  }

  private def hasStreamResourceWithType(forEnumerators: Seq[Enumerator])(implicit doc: SemanticDocument): Boolean = {
    forEnumerators.exists {
      case Enumerator.Generator(_, Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("resource")), _), List(bec))) if isBlockingExecutionContext(bec) => true
      case _ => false
    }
  }

  private def replaceBlockingExecutionContextForYield(forEnumerators: Seq[Enumerator], resourceSet: mutable.Set[Position], resourcesFMap: mutable.Set[Position])(implicit doc: SemanticDocument): Patch = {
    // We assume the existence of only 1 instance of Stream.resource
    val (before, after) = forEnumerators.span {
      case Enumerator.Generator(_, Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("resource")), _)) => true
      case _ => false
    }
    val Enumerator.Generator(_, ap @ Term.Apply(Term.Select(Term.Name("Stream"), Term.Name("resource")), List(bec))) = before.last
    resourceSet += ap.pos
    after.foreach{ resourcesFMap += _.pos }
    val tpe = firstType(getType(bec.symbol)).get
      Patch.replaceTree(bec, s"Blocker[$tpe]") + addCatsEffectImports
  }

  private def replaceBlockingExecutionContextWithTypeForYield(forEnumerators: Seq[Enumerator], resourceSet: mutable.Set[Position], resourcesFMap: mutable.Set[Position])(implicit doc: SemanticDocument): Patch = {
    // We assume the existence of only 1 instance of Stream.resource
    val (before, after) = forEnumerators.span {
      case Enumerator.Generator(_, Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("resource")),_), _)) => true
      case _ => false
    }
    val Enumerator.Generator(_, ap @ Term.Apply(Term.ApplyType(Term.Select(Term.Name("Stream"), Term.Name("resource")),List(_, tpe2)), List(bec))) = before.last
    resourceSet += ap.pos
    after.foreach{ resourcesFMap += _.pos }
    val tpe = firstType(getType(bec.symbol)).get
    List(Patch.replaceTree(tpe2, "Blocker"),
      Patch.replaceTree(bec, s"Blocker[$tpe]")).asPatch + addCatsEffectImports
  }

  private def addBlockerTerm(ap: Term.ApplyType)(implicit doc: SemanticDocument) = {
    Patch.replaceTree(ap, s"$ap(Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))") + addCatsEffectImports
  }

  private def replaceBlockingExecutionContextTerm(bec: Term, ap: Term, resourceFMap: mutable.Set[Position])(implicit doc: SemanticDocument): Patch = {
    val tpe = getType(bec.symbol)
    if (isExecutionContextSubclass(tpe) && !skipTerm(ap, resourceFMap)) {
      Patch.replaceTree(bec, s"Blocker.liftExecutionContext($bec)") + addCatsEffectImports
    } else {
      Patch.empty
    }
  }

  private def replaceBlockingExecutionContextTermAndRenameFunction(bec: Term, ap: Term, resourceFMap: mutable.Set[Position])(fun: Term, newFun: String)(implicit doc: SemanticDocument): Patch = {
    val tpe = getType(bec.symbol)
    if (isExecutionContextSubclass(tpe) && !skipTerm(ap, resourceFMap)) {
      val funTpe =
        fun match {
          case Term.ApplyType(_, List(tpe2)) => Some(tpe2)
          case _ => None
        }

      val newFunWithType = newFun + funTpe.fold("")(tpe => s"[$tpe]")

      List(Patch.replaceTree(fun, newFunWithType),Patch.replaceTree(bec, s"Blocker.liftExecutionContext($bec)")).asPatch + addCatsEffectImports
    } else {
      Patch.empty
    }
  }

  private def isExecutionContextSubclass(tpe: SemanticType): Boolean =
    tpe match {
      case TypeRef(_, symbol, _) => symbol.value.startsWith("scala/concurrent/ExecutionContext")
      case _ => false
    }

  private def skipTerm(ap: Term, resourceFMap: mutable.Set[Position]): Boolean =
    resourceFMap.exists(pos => (pos.start <= ap.pos.start) && (pos.end >= ap.pos.end))

  private def isBlockingExecutionContext(bec: Term)(implicit doc: SemanticDocument): Boolean =
    secondType(getType(bec.symbol)).exists(isExecutionContextSubclass)

  private[this] def addCatsEffectImports(implicit doc: SemanticDocument): Patch =
    if (!containsImport(importer"cats.effect._")) // the other cases are handled directly by scalafix
      Patch.addGlobalImport(Symbol("cats/effect/Blocker."))
    else Patch.empty

  private[this] val resourceMatcher = SymbolMatcher.normalized("fs2/Stream#resource.")
  private[this] val linesAsyncMatcher = SymbolMatcher.normalized("fs2/Stream#linesAsync.")
  private[this] val showLinesAsyncMatcher = SymbolMatcher.normalized("fs2/Stream#showLinesAsync.")
  private[this] val showLinesStdOutAsyncMatcher = SymbolMatcher.normalized("fs2/Stream#showLinesStdOutAsync.")
  private[this] val defaultMatcher = SymbolMatcher.normalized("fs2/io/Watcher#default.")
  private[this] val readAllMatcher = SymbolMatcher.normalized("fs2/io/file/package.readAll.")
  private[this] val readRangeMatcher = SymbolMatcher.normalized("fs2/io/file/package.readRange.")
  private[this] val writeAllMatcher = SymbolMatcher.normalized("fs2/io/file/package.writeAll.")
  private[this] val watcherMatcher = SymbolMatcher.normalized("fs2/io/file/package.watcher.")
  private[this] val watchMatcher = SymbolMatcher.normalized("fs2/io/file/package.watch.")
  private[this] val fromPathMatcher = SymbolMatcher.normalized("fs2/io/file/pulls#fromPath.")
  private[this] val fromFileChannelMatcher = SymbolMatcher.normalized("fs2/io/file/pulls#fromFileChannel.")
  private[this] val readInputStreamMatcher = SymbolMatcher.normalized("fs2/io/package.readInputStream.")
  private[this] val unsafeReadInputStreamMatcher = SymbolMatcher.normalized("fs2/io/package.unsafeReadInputStream.")
  private[this] val stdinMatcher = SymbolMatcher.normalized("fs2/io/package.stdin.")
  private[this] val stdoutMatcher = SymbolMatcher.normalized("fs2/io/package.stdout.")
  private[this] val stdoutLinesMatcher = SymbolMatcher.normalized("fs2/io/package.stdoutLines.")
  private[this] val stdinUtf8Matcher = SymbolMatcher.normalized("fs2/io/package.stdinUtf8.")

}

object SocketGroupRule {

  def apply(tree: Tree)(implicit doc: SemanticDocument): List[Patch] = {
    val resourceFMap = mutable.Map.empty[Position, String]
    tree.collect {
      case sr @ Term.Apply(
                  Term.Select(
                      ap @ Term.Apply(
                      Term.Select(Term.Name("Stream"), Term.Name("resource")),
                      List(bec)
                    ),
                  Term.Name("flatMap") | Term.Name("map")
                  ),
              _)  if secondType(getType(bec.symbol)).exists(isUdpSocketGroup) =>

        val socketGroupName = generateSocketGroupVariable(sr)
        resourceFMap += sr.pos -> socketGroupName

        val skipField = resourceFMap.exists{case (pos, _) => (pos.start < ap.pos.start) && (pos.end > ap.pos.end)}
        if (!skipField) {
          Patch.addLeft(sr, s"Stream.resource(cats.effect.Blocker[IO].flatMap(blocker => fs2.io.udp.SocketGroup(blocker))).flatMap { $socketGroupName =>\n") + Patch.addRight(sr, "}")
            .+(Patch.replaceTree(bec, s"$socketGroupName.$bec")) + Patch.removeGlobalImport(Symbol(s"fs2/io/udp/open."))
        }
        else {
          val (_, socketGroupName2) = resourceFMap.filter{case (pos, _) => (pos.start < ap.pos.start) && (pos.end > ap.pos.end)}.maxBy(_._1.start)
          Patch.replaceTree(bec, s"$socketGroupName2.$bec")
        }

      case sr @ Term.Apply(
                  Term.Select(
                    ap @ Term.Apply(
                      Term.Select(Term.Name("Stream"), Term.Name("resource")),
                      List(bec)
                    ),
                  Term.Name("flatMap") | Term.Name("map")
                  ),
                _)  if secondType(getType(bec.symbol)).exists(isTcpSocketGroup) =>

      // TODO
      Patch.empty
    }
  }

  private def isUdpSocketGroup(tpe: SemanticType): Boolean =
    tpe match {
      case TypeRef(_, symbol, _) => symbol.value.startsWith("fs2/io/udp/Socket#")
      case _ => false
    }

  private def isTcpSocketGroup(tpe: SemanticType): Boolean =
    tpe match {
      case TypeRef(_, symbol, _) => symbol.value.startsWith("fs2/io/tcp/Socket#")
      case _ => false
    }



  private def generateSocketGroupVariable(term: Term): String = {

    def loop(suffix: Int): String = {
      val socketGroupName = "socketGroup" + (if (suffix == 0) "" else suffix.toString)
      val res =
        term.collect {
          case Pat.Var(name) if name.value == socketGroupName => true
          case _ => false
        }
      if (res.contains(true)) loop(suffix + 1) else socketGroupName
    }

    loop(0)
  }

}



