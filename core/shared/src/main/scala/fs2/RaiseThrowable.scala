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

import cats.ApplicativeError
import scala.annotation.implicitNotFound

/** Witnesses that `F` supports raising throwables.
  *
  * An instance of `RaiseThrowable` is available for any `F` which has an
  * `ApplicativeError[F, Throwable]` instance. Alternatively, an instance
  * is available for the uninhabited type `Fallible`.
  */
@implicitNotFound(
  "Cannot find an implicit value for RaiseThrowable[${F}]: an instance is available for any F which has an ApplicativeError[F, Throwable] instance or for F = Fallible. If getting this error for a non-specific F, try manually supplying the type parameter (e.g., Stream.raiseError[IO](t) instead of Stream.raiseError(t)). If getting this error when working with pure streams, use F = Fallible."
)
trait RaiseThrowable[F[_]]

object RaiseThrowable {
  implicit def fromApplicativeError[F[_]](implicit
      F: ApplicativeError[F, Throwable]
  ): RaiseThrowable[F] = {
    val _ = F
    new RaiseThrowable[F] {}
  }
}
