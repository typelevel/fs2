package fs2

import java.io.PrintStream

import cats.Show
import cats.effect.{Concurrent, Sync}
import cats.implicits._

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

  /**
    * Sink that prints each element from the source to the supplied `PrintStream`
    * using the `Show` instance for the input type.
    */
  @deprecated("Use stream.showLines(out) instead", "1.0.2")
  def showLines[F[_]: Sync, I: Show](out: PrintStream): Sink[F, I] =
    _.map(_.show).through(lines(out))

  /**
    * Sink that prints each element from the source to the standard out
    * using the `Show` instance for the input type.
    */
  @deprecated("Use stream.showLinesStdOut instead", "1.0.2")
  def showLinesStdOut[F[_]: Sync, I: Show]: Sink[F, I] = showLines(Console.out)

  /**
    * Sink that routes each element to one of two sinks.
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
