package fs2.internal

import cats.effect.kernel.Unique

private[fs2] object Debug {

  def displayToken(t: Unique.Token): String = t.##.toHexString

  def displayTypeName(a: Any): String = a match {
    case IsFunction(_) => "<fn>"
    case p: Product =>
      val prefix = if (p.productPrefix.startsWith("Tuple")) "" else p.productPrefix
      p.productIterator.map(displayTypeName).mkString(prefix + "(", ",", ")")
    case _ => a.getClass.getSimpleName
  }

  def display(a: Any): String = a match {
    case t: Unique.Token => displayToken(t)
    case IsFunction(_)   => "<fn>"
    case _               => a.toString
  }

  private object IsFunction {
    def unapply(a: Any): Option[Any] = a match {
      case _: Function[_, _] | _: Function2[_, _, _] | _: Function3[_, _, _, _] |
          _: Function4[_, _, _, _, _] | _: Function5[_, _, _, _, _, _] |
          _: Function6[_, _, _, _, _, _, _] | _: Function7[_, _, _, _, _, _, _, _] |
          _: Function8[_, _, _, _, _, _, _, _, _] | _: Function9[_, _, _, _, _, _, _, _, _, _] |
          _: Function10[_, _, _, _, _, _, _, _, _, _, _] |
          _: Function11[_, _, _, _, _, _, _, _, _, _, _, _] |
          _: Function12[_, _, _, _, _, _, _, _, _, _, _, _, _] =>
        Some(a)
      case _ => None
    }
  }
}
