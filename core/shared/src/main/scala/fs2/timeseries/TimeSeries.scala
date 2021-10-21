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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2
package timeseries

import scala.concurrent.duration._

import cats.effect.Temporal

/** A time series is a stream of timestamped values or clock ticks.
  *
  * Values are represented as `Some` values in a `TimeStamped[Option[A]]`, whereas
  * clock ticks are represented as `None`s. This encoding allows for an indication
  * of time passage with no observed values.
  *
  * Generally, time series appear in increasing order, and many combinators that work with
  * time series will rely on that. For streams that are globally ordered, but not locally ordered,
  * i.e., near adjacent values might be out of order but values at great distance from each other
  * are ordered, consider using `TimeStamped.reorderLocally` to adjust.
  */
object TimeSeries {

  /** Stream of either time ticks (spaced by `tickPeriod`) or values from the source stream. */
  def apply[F[_]: Temporal, A](
      source: Stream[F, TimeStamped[A]],
      tickPeriod: FiniteDuration,
      reorderOver: FiniteDuration
  ): Stream[F, TimeStamped[Option[A]]] = {
    val src: Stream[F, TimeStamped[Option[A]]] = source.map(tsa => tsa.map(Some(_): Option[A]))
    val ticks: Stream[F, TimeStamped[Option[Nothing]]] = timeTicks(tickPeriod).map(tsu => tsu.map(_ => None))
    src.merge(ticks).through(TimeStamped.reorderLocally(reorderOver))
  }

  /** Stream of either time ticks (spaced by `tickPeriod`) or values from the source stream. */
  def timePulled[F[_]: Temporal, A](
      source: Stream[F, A],
      tickPeriod: FiniteDuration,
      reorderOver: FiniteDuration
  ): Stream[F, TimeStamped[Option[A]]] =
    apply(source.map(TimeStamped.unsafeNow), tickPeriod, reorderOver)

  /** Lifts a function from `A => B` to a time series pipe. */
  def lift[F[_], A, B](f: A => B): Stream[F, TimeStamped[Option[A]]] => Stream[F, TimeStamped[Option[B]]] =
    _.map(_.map(_.map(f)))

  /** Time series pipe which discards right values. */
  def drainRight[F[_], L, R]: Stream[F, TimeStamped[Option[Either[L, R]]]] => Stream[F, TimeStamped[Option[L]]] = _.collect {
    case tick @ TimeStamped(_, None)    => tick.asInstanceOf[TimeStamped[Option[L]]]
    case TimeStamped(ts, Some(Left(l))) => TimeStamped(ts, Some(l))
  }

  /** Time series pipe which discards left values. */
  def drainLeft[F[_], L, R]: Stream[F, TimeStamped[Option[Either[L, R]]]] => Stream[F, TimeStamped[Option[R]]] = _.collect {
    case tick @ TimeStamped(_, None)     => tick.asInstanceOf[TimeStamped[Option[R]]]
    case TimeStamped(ts, Some(Right(r))) => TimeStamped(ts, Some(r))
  }

  /** Stream of time ticks spaced by `tickPeriod`. */
  private def timeTicks[F[_]: Temporal](tickPeriod: FiniteDuration): Stream[F, TimeStamped[Unit]] =
    Stream.awakeEvery[F](tickPeriod).map(_ => TimeStamped.unsafeNow(()))

  /** Stream transducer that converts a stream of timestamped values with monotonically increasing timestamps in
    * to a stream of timestamped ticks or values, where a tick is emitted every `tickPeriod`.
    * Ticks are emitted between values from the source stream.
    */
  def interpolateTicks[F[_], A](
      tickPeriod: FiniteDuration
  ): Stream[F, TimeStamped[A]] => Stream[F, TimeStamped[Option[A]]] = {
    def go(
        nextTick: FiniteDuration,
        s: Stream[F, TimeStamped[A]]
    ): Pull[F, TimeStamped[Option[A]], Unit] = {
      def tickTime(x: Int) = nextTick + (x * tickPeriod)
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          hd.indexWhere(_.time >= nextTick) match {
            case None =>
              Pull.output(hd.map(_.map(Some(_)))) >> go(nextTick, tl)
            case Some(idx) =>
              val (prefix, suffix) = hd.splitAt(idx)
              val out = Pull.output(prefix.map(_.map(Some(_))))
              // we know suffix is non-empty and suffix.head has a time >= next tick time
              val next = suffix(0)
              val tickCount =
                ((next.time.toMillis - nextTick.toMillis) / tickPeriod.toMillis + 1).toInt
              val tickTimes = (0 until tickCount).map(tickTime)
              val ticks = tickTimes.map(TimeStamped.tick)
              val rest = Pull.output(Chunk.seq(ticks)) >> go(tickTime(tickCount), tl.cons(suffix))
              out >> rest
          }
        case None => Pull.done
      }
    }
    in =>
      in.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          Pull.output1(hd.map(Some(_))) >> go(hd.time + tickPeriod, tl)
        case None => Pull.done
      }.stream
  }

  /** Combinator that converts a `Scan[S, I, O]` in to a `Scan[S, TimeStamped[Option[I]], TimeStamped[Option[O]]]` such that
    * timestamps are preserved on elements that flow through the stream.
    */
  def preserve[S, I, O](t: Scan[S, I, O]): Scan[S, TimeStamped[Option[I]], TimeStamped[Option[O]]] =
    preserveTicks(TimeStamped.preserve(t))

  /** Combinator that converts a `Scan[S, TimeStamped[I], TimeStamped[O]]` in to a `Scan[S, TimeStamped[Option[I]], TimeStamped[Option[O]]]` such that
    * timestamps are preserved on elements that flow through the stream.
    */
  def preserveTicks[S, I, O](
      t: Scan[S, TimeStamped[I], TimeStamped[O]]
  ): Scan[S, TimeStamped[Option[I]], TimeStamped[Option[O]]] =
    t.semilens(
      tsi =>
        tsi.value
          .map(v => Right(TimeStamped(tsi.time, v)))
          .getOrElse(Left(TimeStamped.tick(tsi.time))),
      (_, tso) => tso.map(Some(_))
    )

  /** Combinator that combines a `Scan[LS, TimeStamped[Option[L]], O]` and a `Scan[RS, TimeStamped[Option[R]], O]` in to a `Scan[(LS, RS), TimeSeriesVlaue[Either[L, R], O]]`.
    */
  def choice[LS, L, RS, R, O](
      l: Scan[LS, TimeStamped[Option[L]], O],
      r: Scan[RS, TimeStamped[Option[R]], O]
  ): Scan[(LS, RS), TimeStamped[Option[Either[L, R]]], O] =
    Scan[(LS, RS), TimeStamped[Option[Either[L, R]]], O]((l.initial, r.initial))(
      { case ((lState, rState), tsv) =>
        tsv match {
          case TimeStamped(t, Some(Left(lValue))) =>
            val (s, out) = l.transform(lState, TimeStamped(t, Some(lValue)))
            (s -> rState, out)
          case TimeStamped(t, Some(Right(rValue))) =>
            val (s, out) = r.transform(rState, TimeStamped(t, Some(rValue)))
            (lState -> s, out)
          case TimeStamped(t, None) =>
            val (ls, lout) = l.transform(lState, TimeStamped(t, None))
            val (rs, rout) = r.transform(rState, TimeStamped(t, None))
            ((ls, rs), lout ++ rout)
        }
      },
      { case (lState, rState) => l.onComplete(lState) ++ r.onComplete(rState) }
    )
}
