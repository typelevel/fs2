package fix

import scalafix.v1._

import scala.meta._

object BracketRules {
  def unapply(t: Tree): Option[Patch] =
    Some(
      t.children
        .flatMap(traverse)
        .asPatch)

  def traverse(t: Tree): Option[Patch] =
    t match {
      case d: Defn.Val =>
        Some(replaceBracket(d.rhs))
      case d: Defn.Def =>
        Some(replaceBracket(d.body))
      case d: Defn.Var =>
        d.rhs.map(replaceBracket)
      case b: Term.ForYield =>
        Some(b.enums.collect {
          case e: Enumerator.Generator => replaceBracket(e.rhs)
          case e: Enumerator.Val       => replaceBracket(e.rhs)
          case e: Enumerator.Guard     => replaceBracket(e.cond)
        }.asPatch)
      case b: Term.For =>
        Some(b.enums.collect {
          case e: Enumerator.Generator => replaceBracket(e.rhs)
          case e: Enumerator.Val       => replaceBracket(e.rhs)
          case e: Enumerator.Guard     => replaceBracket(e.cond)
        }.asPatch)
      case b: Term.Apply =>
        Some(b.args.map(replaceBracket).asPatch)
      case b: Term.Tuple =>
        Some(b.args.map(replaceBracket).asPatch)
      case b: Term.Block =>
        Some(b.stats.map {
          case s: Term.Apply => replaceBracket(s)
          case _             => Patch.empty
        }.asPatch)
      case _ => None
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
      case b => traverse(b).asPatch
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

  def containsBracket(p: Tree): Boolean =
    p.collect {
        case Term.Apply(Term.Apply(Term.Select(_, Term.Name("bracket")), _), _) =>
          true
        case _ =>
          false
      }
      .exists(identity)
}
