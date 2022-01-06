/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.Id
import cats.kernel.Monoid
import cats.data.Chain
import cats.effect.kernel.{Outcome, Unique}
import cats.syntax.all._

import fs2.internal.Debug

sealed trait ScopeSnapshot {
  def id: Unique.Token
  def open: Boolean
  def active: Boolean
  def children: Chain[ScopeSnapshot]
  def resources: Chain[ResourceSnapshot]
  def interruptible: Option[InterruptContextSnapshot]

  def activate(id: Unique.Token): ScopeSnapshot
  def withActive(active: Boolean): ScopeSnapshot
  def withChildren(children: Chain[ScopeSnapshot]): ScopeSnapshot

  def withSimpleResourceDetails: ScopeSnapshot
  def withNoResourceDetails: ScopeSnapshot
  def mapResources(f: Option[Any] => Option[Any]): ScopeSnapshot

  def toDot(includeInnteruptRootEdges: Boolean = false): String = {
    def id(t: Unique.Token): String = t.##.toString
    def renderOutcome(o: Outcome[Id, Throwable, Unique.Token]): String = o match {
      case Outcome.Succeeded(_) => "Succeeded"
      case Outcome.Errored(t)   => s"Errored(${t.getMessage})"
      case Outcome.Canceled()   => "Canceled"
    }
    def escape(s: String): String = s.replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    def table(rows: Option[String]*): String = {
      val bldr = new collection.mutable.StringBuilder
      bldr ++= """<table border="0" cellborder="0" cellspacing="1">"""
      rows.flatten.foreach { s =>
        bldr ++= "<tr><td>"
        bldr ++= s
        bldr ++= "</td></tr>"
      }
      bldr ++= """</table>"""
      bldr.result()
    }

    val subgraph = ScopeSnapshot.walk { s =>
      val node = {
        val t = table(
          Some(s"<b>Scope ${Debug.displayToken(s.id)}</b>"),
          s.interruptible.map(i =>
            i.outcome match {
              case Some(outcome) => s"Interruption outcome: ${renderOutcome(outcome)}"
              case None          => "Interruptible"
            }
          ),
          Option("Closed").filterNot(_ => s.open),
          Option("Active").filter(_ => s.active)
        )
        s"""  ${id(s.id)} [label=<$t> penwidth=${if (s.active) 2 else 1}]"""
      }
      val interruptionEdges = Chain.fromSeq(
        List(
          s.interruptible
            .map(i => s"  ${id(s.id)} -> ${id(i.interruptRoot)} [style=dashed]")
            .filter(_ => includeInnteruptRootEdges),
          s.interruptible.flatMap(_.outcome.collect { case Outcome.Succeeded(t) =>
            s"  ${id(s.id)} -> ${id(t)} [style=dotted label=interrupt]"
          })
        ).flatten
      )
      val edges =
        interruptionEdges ++
          s.resources.flatMap { r =>
            val t = table(
              Some(s"<b>Resource ${Debug.displayToken(r.id)}</b>"),
              r.resource.map(a => escape(a.toString))
            )
            Chain(
              s"  ${id(r.id)} [label=<$t>]",
              s"  ${id(s.id)} -> ${id(r.id)}"
            )
          } ++
          s.children.map(c => s"  ${id(s.id)} -> ${id(c.id)}")
      node +: edges
    }(this)
    val font = "Helvetica,Arial"
    val lines = Chain(
      "digraph {",
      s"""  node [shape=rectangle fontname="$font"]""",
      s"""  edge [fontname="$font"]"""
    ) ++ subgraph ++ Chain.one("}")
    lines.iterator.mkString("\n")
  }
}
object ScopeSnapshot {
  def apply(
      id0: Unique.Token,
      open0: Boolean,
      active0: Boolean,
      children0: Chain[ScopeSnapshot],
      resources0: Chain[ResourceSnapshot],
      interruptible0: Option[InterruptContextSnapshot]
  ): ScopeSnapshot =
    new ScopeSnapshot {
      def id = id0
      def open = open0
      def active = active0
      def children = children0
      def resources = resources0
      def interruptible = interruptible0

      def activate(id: Unique.Token) =
        if (this.id == id) withActive(true)
        else withChildren(children.map(_.activate(id)))

      def withActive(active0: Boolean) =
        apply(id, open, active0, children, resources, interruptible)
      def withChildren(children0: Chain[ScopeSnapshot]) =
        apply(id, open, active, children0, resources, interruptible)

      def withSimpleResourceDetails: ScopeSnapshot =
        mapResources(_.map(Debug.displayTypeName))

      def withNoResourceDetails: ScopeSnapshot =
        mapResources(_ => None)

      def mapResources(f: Option[Any] => Option[Any]): ScopeSnapshot = {
        val resources0 = resources.map(_.mapResource(f))
        apply(id, open, active, children.map(_.mapResources(f)), resources0, interruptible)
      }
    }

  private def walk[A: Monoid](f: ScopeSnapshot => A)(scope: ScopeSnapshot): A =
    f(scope) |+| scope.children.foldMap(walk(f))
}

sealed trait ResourceSnapshot {
  def id: Unique.Token
  def resource: Option[Any]
  def mapResource(f: Option[Any] => Option[Any]): ResourceSnapshot
}

object ResourceSnapshot {
  def apply(id0: Unique.Token, resource0: Option[Any]): ResourceSnapshot =
    new ResourceSnapshot {
      def id = id0
      def resource = resource0
      def mapResource(f: Option[Any] => Option[Any]) = apply(id, f(resource))
    }
}

sealed trait InterruptContextSnapshot {
  def interruptRoot: Unique.Token
  def outcome: Option[Outcome[Id, Throwable, Unique.Token]]
}

object InterruptContextSnapshot {
  def apply(
      interruptRoot0: Unique.Token,
      outcome0: Option[Outcome[Id, Throwable, Unique.Token]]
  ): InterruptContextSnapshot =
    new InterruptContextSnapshot {
      def interruptRoot = interruptRoot0
      def outcome = outcome0
    }
}
