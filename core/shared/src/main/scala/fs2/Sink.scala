package fs2

import cats.Show
import cats.effect.Sync
import cats.implicits._

import java.io.PrintStream

/** Companion for [[Sink]]. */
object Sink {

  /** Lifts a function `I => F[Unit]` to `Sink[F,I]`. */
  def apply[F[_],I](f: I => F[Unit]): Sink[F,I] = _.evalMap(f)

  /** Sink that prints each string from the source to the supplied `PrintStream`. */
  def lines[F[_]](out: PrintStream)(implicit F: Sync[F]): Sink[F,String] =
    apply(str => F.delay(out.println(str)))

  /**
   * Sink that prints each element from the source to the supplied `PrintStream`
   * using the `Show` instance for the input type.
   */
  def showLines[F[_]: Sync, I: Show](out: PrintStream): Sink[F,I] =
    _.map(_.show).to(lines(out))

  /**
   * Sink that prints each element from the source to the standard out
   * using the `Show` instance for the input type.
   */
  def showLinesStdOut[F[_]: Sync, I: Show]: Sink[F,I] = showLines(Console.out)
}
