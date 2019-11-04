package fs2
package benchmark

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.annotation.compileTimeOnly

@compileTimeOnly("enable macro paradise to expand macro annotations")
final class GenerateN(val ns: Int*) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro GenerateNMacro.transform_impl
}

import scala.reflect.macros.whitebox

final class GenerateNMacro(val c: whitebox.Context) {
  import c.universe._

  def transform_impl(annottees: c.Expr[Any]*): c.Tree = {
    val ns = c.prefix.tree match {
      case q"new $_(..$ns)" => ns.map(t => c.eval[Int](c.Expr(t)))
    }
    val ts = annottees.map(_.tree) match {
      case List(q"""$as def $name($nparam: Int): $ret = $body""") =>
        ns.map { n =>
          val tn = TermName(s"${name}_$n")
          q"""$as def $tn(): $ret = {
            val $nparam = $n
            ..$body }"""
        }
      case t =>
        c.abort(c.enclosingPosition, "unable to generate benchmark")
    }
    q"..$ts"
  }
}
