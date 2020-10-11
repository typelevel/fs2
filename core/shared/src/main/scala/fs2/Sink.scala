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

import java.io.PrintStream

import cats.Show
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._

/** Companion for [[Sink]]. */
@deprecated("Use Pipe instead", "1.0.2")
object Sink {

  /** Lifts a function `I => F[Unit]` to `Sink[F,I]`. */
  @deprecated("Use stream.evalMap(f) instead", "1.0.2")
  def apply[F[_], I](f: I => F[Unit]): Sink[F, I] = _.evalMap(f)

  /** Sink that prints each string from the source to the supplied `PrintStream`. */
  @deprecated("Use stream.lines(out) instead", "1.0.2")
  def lines[F[_]](out: PrintStream)(implicit F: Sync[F]): Sink[F, String] =
    apply(str => F.delay(out.println(str)))

  /** Sink that prints each element from the source to the supplied `PrintStream`
    * using the `Show` instance for the input type.
    */
  @deprecated("Use stream.showLines(out) instead", "1.0.2")
  def showLines[F[_]: Sync, I: Show](out: PrintStream): Sink[F, I] =
    _.map(_.show).through(lines(out))

  /** Sink that prints each element from the source to the standard out
    * using the `Show` instance for the input type.
    */
  @deprecated("Use stream.showLinesStdOut instead", "1.0.2")
  def showLinesStdOut[F[_]: Sync, I: Show]: Sink[F, I] = showLines(Console.out)

  /** Sink that routes each element to one of two sinks.
    * `Left` values get sent to the `left` sink, and likewise for `Right`
    *
    * If either of `left` or `right` fails, then resulting stream will fail.
    * If either `halts` the evaluation will halt too.
    */
  @deprecated("Use stream.observeEither(left, right)", "1.0.2")
  def either[F[_]: Concurrent, L, R](
      left: Sink[F, L],
      right: Sink[F, R]
  ): Sink[F, Either[L, R]] =
    _.observe(_.collect { case Left(l) => l }.through(left))
      .through(_.collect { case Right(r) => r }.through(right))
}
