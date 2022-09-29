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

import cats.{Functor, Monoid}
import cats.data.Chain
import cats.syntax.all._
import cats.effect.kernel.{Clock, Temporal}

/** Wrapper that associates a time with a value. */
case class TimeStamped[+A](time: FiniteDuration, value: A) {
  def map[B](f: A => B): TimeStamped[B] = copy(value = f(value))
  def mapTime(f: FiniteDuration => FiniteDuration): TimeStamped[A] = copy(time = f(time))
}

object TimeStamped {

  @deprecated("Use unsafeRealTime or unsafeMonotonic", "3.2.10")
  def unsafeNow[A](a: A): TimeStamped[A] = unsafeRealTime(a)

  @deprecated("Use realTime or monotonic", "3.2.10")
  def now[F[_]: Functor: Clock, A](a: A): F[TimeStamped[A]] = realTime[F, A](a)

  def unsafeRealTime[A](a: A): TimeStamped[A] = TimeStamped(System.currentTimeMillis().millis, a)

  def realTime[F[_]: Functor: Clock, A](a: A): F[TimeStamped[A]] =
    Clock[F].realTime.map(TimeStamped(_, a))

  def unsafeMonotonic[A](a: A): TimeStamped[A] = TimeStamped(System.nanoTime().nanos, a)

  def monotonic[F[_]: Functor: Clock, A](a: A): F[TimeStamped[A]] =
    Clock[F].monotonic.map(TimeStamped(_, a))

  def tick(time: FiniteDuration): TimeStamped[Option[Nothing]] = TimeStamped(time, None)

  /** Orders values by timestamp -- values with the same timestamp are considered equal. */
  def timeBasedOrdering[A]: Ordering[TimeStamped[A]] = new Ordering[TimeStamped[A]] {
    def compare(x: TimeStamped[A], y: TimeStamped[A]) = x.time.compareTo(y.time)
  }

  /** Orders values by timestamp, then by value. */
  implicit def ordering[A](implicit A: Ordering[A]): Ordering[TimeStamped[A]] =
    new Ordering[TimeStamped[A]] {
      def compare(x: TimeStamped[A], y: TimeStamped[A]) = x.time.compareTo(y.time) match {
        case 0     => A.compare(x.value, y.value)
        case other => other
      }
    }

  /** Combinator that converts a `Scan[A, B]` in to a `Scan[TimeStamped[A], TimeStamped[B]]` such that
    * timestamps are preserved on elements that flow through the stream.
    */
  def preserve[S, I, O](t: Scan[S, I, O]): Scan[S, TimeStamped[I], TimeStamped[O]] =
    t.lens(_.value, (tsi, o) => tsi.copy(value = o))

  /** Scan that converts a stream of `TimeStamped[A]` in to a stream of
    * `TimeStamped[B]` where `B` is an accumulated feature of `A` over a second.
    *
    * For example, the emitted bits per second of a `Stream[F, ByteVector]` can be calculated
    * using `perSecondRate(_.size * 8)`, which yields a stream of the emitted bits per second.
    *
    * @param f function which extracts a feature of `A`
    */
  def perSecondRate[A, B: Monoid](
      f: A => B
  ): Scan[(Option[FiniteDuration], B), TimeStamped[A], TimeStamped[B]] =
    rate(1.second)(f)

  /** Scan that converts a stream of `TimeStamped[A]` in to a stream of
    * `TimeStamped[B Either A]` where `B` is an accumulated feature of `A` over a second.
    *
    * Every incoming `A` is echoed to the output.
    *
    * For example, the emitted bits per second of a `Stream[F, ByteVector]` can be calculated
    * using `perSecondRate(_.size * 8)`, which yields a stream of the emitted bits per second.
    *
    * @param f function which extracts a feature of `A`
    */
  def withPerSecondRate[A, B: Monoid](
      f: A => B
  ): Scan[(Option[FiniteDuration], B), TimeStamped[A], TimeStamped[Either[B, A]]] =
    withRate(1.second)(f)

  /** Scan that converts a stream of `TimeStamped[A]` in to a stream of
    * `TimeStamped[B]` where `B` is an accumulated feature of `A` over a specified time period.
    *
    * For example, the emitted bits per second of a `Stream[F, ByteVector]` can be calculated
    * using `rate(1.0)(_.size * 8)`, which yields a stream of the emitted bits per second.
    *
    * @param over time period over which to calculate
    * @param f function which extracts a feature of `A`
    */
  def rate[A, B: Monoid](
      over: FiniteDuration
  )(f: A => B): Scan[(Option[FiniteDuration], B), TimeStamped[A], TimeStamped[B]] = {
    val t = withRate(over)(f)
    Scan(t.initial)(
      (s, tsa) => {
        val (s2, out) = t.transform(s, tsa)
        (s2, out.collect { case TimeStamped(ts, Left(b)) => TimeStamped(ts, b) })
      },
      s => t.onComplete(s).collect { case TimeStamped(ts, Left(b)) => TimeStamped(ts, b) }
    )
  }

  /** Scan that converts a stream of `TimeStamped[A]` in to a stream of
    * `TimeStamped[Either[B, A]]` where `B` is an accumulated feature of `A` over a specified time period.
    *
    * Every incoming `A` is echoed to the output.
    *
    * For example, the emitted bits per second of a `Stream[F, ByteVector]` can be calculated
    * using `rate(1.second)(_.size * 8)`, which yields a stream of the emitted bits per second.
    *
    * @param over time period over which to calculate
    * @param f function which extracts a feature of `A`
    */
  def withRate[A, B](over: FiniteDuration)(f: A => B)(implicit
      B: Monoid[B]
  ): Scan[(Option[FiniteDuration], B), TimeStamped[A], TimeStamped[Either[B, A]]] =
    Scan[(Option[FiniteDuration], B), TimeStamped[A], TimeStamped[Either[B, A]]](None -> B.empty)(
      { case ((end, acc), tsa) =>
        end match {
          case Some(end) =>
            if (tsa.time < end)
              (Some(end) -> B.combine(acc, f(tsa.value)), Chunk(tsa.map(Right.apply)))
            else {
              val bldr = List.newBuilder[TimeStamped[Either[B, A]]]
              var e2 = end
              var acc2 = acc
              while (tsa.time >= e2) {
                bldr += TimeStamped(e2, Left(acc2))
                acc2 = B.empty
                e2 = e2 + over
              }
              bldr += (tsa.map(Right.apply))
              ((Some(e2), f(tsa.value)), Chunk.seq(bldr.result()))
            }
          case None => ((Some(tsa.time + over), f(tsa.value)), Chunk(tsa.map(Right.apply)))
        }
      },
      {
        case (Some(end), acc) => Chunk(TimeStamped(end, Left(acc)))
        case (None, _)        => Chunk.empty
      }
    )

  /** Returns a stream that is the throttled version of the source stream.
    *
    * Given two adjacent items from the source stream, `a` and `b`, where `a` is emitted
    * first and `b` is emitted second, their time delta is `b.time - a.time`.
    *
    * This function creates a stream that emits values at wall clock times such that
    * the time delta between any two adjacent values is proportional to their time delta
    * in the source stream.
    *
    * The `throttlingFactor` is a scaling factor that determines how much source time a unit
    * of wall clock time is worth. A value of 1.0 causes the output stream to emit
    * values spaced in wall clock time equal to their time deltas. A value of 2.0
    * emits values at twice the speed of wall clock time.
    *
    * This is particularly useful when timestamped data can be read in bulk (e.g., from a capture file)
    * but should be "played back" at real time speeds.
    */
  def throttle[F[_]: Temporal, A](
      throttlingFactor: Double,
      tickResolution: FiniteDuration
  ): Pipe[F, TimeStamped[A], TimeStamped[A]] = {

    val ticksPerSecond = 1.second.toMillis / tickResolution.toMillis

    require(ticksPerSecond > 0L, "tickResolution must be <= 1 second")

    def doThrottle: Pipe2[F, TimeStamped[A], Unit, TimeStamped[A]] = {

      type PullFromSourceOrTicks =
        (
            Stream.StepLeg[F, TimeStamped[A]],
            Stream.StepLeg[F, Unit]
        ) => Pull[F, TimeStamped[A], Unit]

      def takeUpto(
          chunk: Chunk[TimeStamped[A]],
          upto: FiniteDuration
      ): (Chunk[TimeStamped[A]], Chunk[TimeStamped[A]]) = {
        val toTake = chunk.indexWhere(_.time > upto).getOrElse(chunk.size)
        chunk.splitAt(toTake)
      }

      def read(upto: FiniteDuration): PullFromSourceOrTicks = { (src, ticks) =>
        if (src.head.isEmpty) {
          src.stepLeg.flatMap {
            case Some(l) => read(upto)(l, ticks)
            case None    => Pull.done
          }
        } else {
          val (toOutput, pending) = takeUpto(src.head, upto)
          if (pending.isEmpty) Pull.output(toOutput) >> read(upto)(src.setHead(Chunk.empty), ticks)
          else Pull.output(toOutput) >> awaitTick(upto, pending)(src.setHead(Chunk.empty), ticks)
        }
      }

      def awaitTick(upto: FiniteDuration, pending: Chunk[TimeStamped[A]]): PullFromSourceOrTicks = {
        (src, ticks) =>
          if (ticks.head.isEmpty) {
            ticks.stepLeg.flatMap {
              case Some(leg) => awaitTick(upto, pending)(src, leg)
              case None      => Pull.done
            }
          } else {
            val tl = ticks.setHead(ticks.head.drop(1))
            val newUpto = upto + ((1000 / ticksPerSecond) * throttlingFactor).toLong.millis
            val (toOutput, stillPending) = takeUpto(pending, newUpto)
            Pull.output(toOutput) >> {
              if (stillPending.isEmpty) read(newUpto)(src, tl)
              else awaitTick(newUpto, stillPending)(src, tl)
            }
          }
      }

      (src, ticks) =>
        src.pull.stepLeg.flatMap {
          case Some(leg) =>
            val head = leg.head(0)
            val tl = leg.head.drop(1)
            Pull.output1(head) >>
              ticks.pull.stepLeg.flatMap {
                case Some(ticksLeg) =>
                  read(head.time)(leg.setHead(tl), ticksLeg)
                case None => Pull.done
              }
          case None => Pull.done
        }.stream
    }

    source => source.through2(Stream.awakeEvery[F](tickResolution).as(()))(doThrottle)
  }

  /** Scan that filters the specified timestamped values to ensure
    * the output time stamps are always increasing in time. Other values are
    * dropped.
    */
  def increasing[F[_], A]: Pipe[F, TimeStamped[A], TimeStamped[A]] =
    increasingEither.andThen(_.collect { case Right(out) => out })

  /** Scan that filters the specified timestamped values to ensure
    * the output time stamps are always increasing in time. The increasing values
    * are emitted wrapped in `Right`, while out of order values are emitted in `Left`.
    */
  def increasingEither[F[_], A]: Pipe[F, TimeStamped[A], Either[TimeStamped[A], TimeStamped[A]]] =
    _.scanChunks(Duration.MinusInf: Duration) { (last, chunk) =>
      chunk.mapAccumulate(last) { (last, tsa) =>
        val now = tsa.time
        if (last <= now) (now, Right(tsa)) else (last, Left(tsa))
      }
    }

  /** Scan that reorders a stream of timestamped values that are mostly ordered,
    * using a time based buffer of the specified duration. See [[attemptReorderLocally]] for details.
    *
    * The resulting stream is guaranteed to always emit values in time increasing order.
    * Values may be dropped from the source stream if they were not successfully reordered.
    */
  def reorderLocally[F[_], A](over: FiniteDuration): Pipe[F, TimeStamped[A], TimeStamped[A]] =
    reorderLocallyEither(over).andThen(_.collect { case Right(tsa) => tsa })

  /** Scan that reorders a stream of timestamped values that are mostly ordered,
    * using a time based buffer of the specified duration. See [[attemptReorderLocally]] for details.
    *
    * The resulting stream is guaranteed to always emit output values in time increasing order,
    * wrapped in `Right`.  Any values that could not be reordered due to insufficient buffer space
    * are emitted wrapped in `Left`.
    */
  def reorderLocallyEither[F[_], A](
      over: FiniteDuration
  ): Pipe[F, TimeStamped[A], Either[TimeStamped[A], TimeStamped[A]]] =
    attemptReorderLocally(over).andThen(increasingEither)

  /** Scan that reorders timestamped values over a specified duration.
    *
    * Values are kept in an internal buffer. Upon receiving a new value, any buffered
    * values that are timestamped with `value.time - over` are emitted. Other values,
    * and the new value, are kept in the buffer.
    *
    * This is useful for ordering mostly ordered streams, where values
    * may be out of order with close neighbors but are strictly less than values
    * that come much later in the stream.
    *
    * An example of such a structure is the result of merging streams of values generated
    * with `TimeStamped.now`.
    *
    * Caution: this scan should only be used on streams that are mostly ordered.
    * In the worst case, if the source is in reverse order, all values in the source
    * will be accumulated in to the buffer until the source halts, and then the
    * values will be emitted in order.
    */
  def attemptReorderLocally[F[_], A](
      over: FiniteDuration
  ): Pipe[F, TimeStamped[A], TimeStamped[A]] = {
    import scala.collection.immutable.SortedMap

    def outputMapValues(m: SortedMap[FiniteDuration, Chain[TimeStamped[A]]]) =
      Pull.output(
        Chunk.chain(
          m.foldLeft(Chain.empty[TimeStamped[A]]) { case (acc, (_, tss)) => acc ++ tss }
        )
      )

    def go(
        buffered: SortedMap[FiniteDuration, Chain[TimeStamped[A]]],
        s: Stream[F, TimeStamped[A]]
    ): Pull[F, TimeStamped[A], Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          val all = Chain.fromSeq(hd.toList).foldLeft(buffered) { (acc, tsa) =>
            val k = tsa.time
            acc.updated(k, acc.getOrElse(k, Chain.empty) :+ tsa)
          }
          if (all.isEmpty) go(buffered, tl)
          else {
            val until = all.last._1 - over
            val (toOutput, toBuffer) = all.span { case (x, _) => x <= until }
            outputMapValues(toOutput) >> go(toBuffer, tl)
          }
        case None =>
          outputMapValues(buffered)
      }

    in => go(SortedMap.empty, in).stream
  }

  def left[S, I, O, A](
      t: Scan[S, TimeStamped[I], TimeStamped[O]]
  ): Scan[S, TimeStamped[Either[I, A]], TimeStamped[Either[O, A]]] =
    t.semilens(
      {
        case TimeStamped(t, Left(i))  => Right(TimeStamped(t, i))
        case TimeStamped(t, Right(a)) => Left(TimeStamped(t, Right(a)))
      },
      (_, tso) => tso.map(Left(_))
    )

  def right[S, I, O, A](
      t: Scan[S, TimeStamped[I], TimeStamped[O]]
  ): Scan[S, TimeStamped[Either[A, I]], TimeStamped[Either[A, O]]] =
    t.semilens(
      {
        case TimeStamped(t, Right(i)) => Right(TimeStamped(t, i))
        case TimeStamped(t, Left(a))  => Left(TimeStamped(t, Left(a)))
      },
      (_, tso) => tso.map(Right(_))
    )

  object syntax {
    implicit class AtSyntax[A](private val value: A) extends AnyVal {
      def at(d: FiniteDuration): TimeStamped[A] =
        TimeStamped(d, value)
    }
  }

  implicit val functor: Functor[TimeStamped[*]] = new Functor[TimeStamped[*]] {
    def map[A, B](fa: TimeStamped[A])(f: A => B): TimeStamped[B] = fa.map(f)
  }
}
