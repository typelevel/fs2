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
