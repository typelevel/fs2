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

import cats.Monad
import cats.syntax.all._

/** Represents a unique identifier (using object equality). */
final class Token private () extends Serializable {
  override def toString: String = s"Token(${hashCode.toHexString})"
}

object Token {

  /**
    * Token provides uniqueness by relying on object equality,
    * which means that `new Token` is actually a side-effect
    * in this case.
    *
    * We want to make sure we preserve referential transparency when
    * using tokens, but imposing a `Sync` bound is not desirable here,
    * since it forces a very strong constraint on user code for
    * something that is not only an internal implementation detail,
    * but also has zero impact on meaningful behaviour for the user,
    * unlike for example internal concurrency.
    *
    * Furthermore, token creation has the several properties:
    * - it's synchronous
    * - it's infallible
    * - it's not created in contexts that affect stack safety such as iteration
    *
    * Given all these reasons, we suspend it via `Monad` instead of
    * using `Sync.` Do not try this at home.
    */
  def apply[F[_]: Monad]: F[Token] =
    ().pure[F].map(_ => new Token)
}
