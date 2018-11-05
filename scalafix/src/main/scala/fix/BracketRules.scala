package fix

import scalafix.v1._

import scala.meta._

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
