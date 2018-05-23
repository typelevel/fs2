package fs2

import java.io.PrintStream

import cats.Show
import cats.effect.{Effect, Sync}
import cats.implicits._

import scala.concurrent.ExecutionContext

/** Companion for [[Sink]]. */
object Sink {

  /** Lifts a function `I => F[Unit]` to `Sink[F,I]`. */
  def apply[F[_], I](f: I => F[Unit]): Sink[F, I] = _.evalMap(f)

  /** Sink that prints each string from the source to the supplied `PrintStream`. */
  def lines[F[_]](out: PrintStream)(implicit F: Sync[F]): Sink[F, String] =
    apply(str => F.delay(out.println(str)))

  /**
    * Sink that prints each element from the source to the supplied `PrintStream`
    * using the `Show` instance for the input type.
    */
  def showLines[F[_]: Sync, I: Show](out: PrintStream): Sink[F, I] =
    _.map(_.show).to(lines(out))

  /**
    * Sink that prints each element from the source to the standard out
    * using the `Show` instance for the input type.
    */
  def showLinesStdOut[F[_]: Sync, I: Show]: Sink[F, I] = showLines(Console.out)

  /**
    * Sink that routes each element to one of two sinks.
    * `Left` values get sent to the `left` sink, and likewise for `Right`
    *
    * If either of `left` or `right` fails, then resulting stream will fail.
    * If either `halts` the evaluation will halt too.
    */
  def either[F[_]: Effect, L, R](
      left: Sink[F, L],
      right: Sink[F, R]
  )(
      implicit ec: ExecutionContext
  ): Sink[F, Either[L, R]] =
    _.observe(_.collect { case Left(l) => l } to left)
      .to(_.collect { case Right(r) => r } to right)
}
