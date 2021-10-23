# Timeseries

The `fs2.timeseries` package provides various utilities for working with time stamped values.

The `TimeStamped` type associates a time stamp, represented as a Unix epoch, with a value:

```scala
case class TimeStamped[+A](time: FiniteDuration, value: A)
```

There's a bunch of interesting things we can do with streams of time stamped values. For example, we can compute bitrates of binary streams:

```scala
import fs2.Stream
import fs2.timeseries.TimeStamped
import scodec.bits.ByteVector

def withBitrate[F[_]](input: Stream[F, TimeStamped[ByteVector]]): Stream[F, TimeStamped[Either[Long, ByteVector]]] =
  TimeStamped.withPerSecondRate[ByteVector, Long](_.size * 8).toPipe(input)
```

The `TimeStamped.withPerSecondRate` function let's us aggregate a "feature" of a sequence of values received in each one second period. In this case, our feature is the number of bits, resulting in a bitrate. These computed bitrates are emitted in the output stream.

Our `withBitrate` combinator requires a `Stream[F, TimeStamped[ByteVector]]` argument, whereas we'd normally have a `Stream[F, Byte]` when working with binary -- e.g., when reading network sockets.

```scala
def withReceivedBitrate[F[_]](input: Stream[F, Byte]): Stream[F, TimeStamped[Either[Long, ByteVector]]] =
  input.chunks.map(c => TimeStamped.unsafeNow(c.toByteVector)).through(withBitrate)
```

Each emitted sample is the sum of bits received during each one second period. Let's compute an average of that value over the last 10 seconds. We can do this via `mapAccumulate` along with a `scala.collection.immutable.Queue`:

```scala
import scala.collection.immutable.Queue

def withAverageBitrate[F[_]](input: Stream[F, Byte]): Stream[F, TimeStamped[Either[Long, ByteVector]]] =
  withReceivedBitrate(input).mapAccumulate(Queue.empty[Long]) {
    case (q, tsv @ TimeStamped(_, Right(_))) => (q, tsv)
    case (q, TimeStamped(t, Left(sample))) => 
      val q2 = (sample +: q).take(10)
      val average = q2.sum / q2.size
      (q, TimeStamped(t, Left(average)))
  }.map(_._2)
```

We can then store the computed bitrates in a `Ref` for later viewing / logging / etc.

```scala
import cats.effect.Ref
import fs2.Chunk

def measureAverageBitrate[F[_]](store: Ref[F, Long], input: Stream[F, Byte]): Stream[F, Byte] =
  withAverageBitrate(input).flatMap {
    case TimeStamped(_, Left(bitrate)) => Stream.exec(store.set(bitrate))
    case TimeStamped(_, Right(bytes)) => Stream.chunk(Chunk.byteVector(bytes))
  }
```

## Time Series

All good, right? But wait, what happens if we stop receiving data for a while? There will be no input to `TimeStamped.withPerSecondRate`, which means there will be no output as well, and that means our `Ref` will not be updated -- it will be frozen with the last value! We need to `Ref` to accurately reflect the loss of data, which means we need some way to all `withPerSecondRate` to emit zero values as time passes.

To accomplish this, we can modify our bitrate calculation to operate on values of type `TimeStamped[Option[ByteVector]]`, where a timestamped `None` represents a "tick" of the clock. A stream of timestamped options is referred to as a "time series" and the `fs2.timeseries.TimeSeries` object defines various ways to build such streams.

First, let's adjust `withBitrate` so that it operates on `TimeStamped[Option[ByteVector]]` values:

```scala
import fs2.Stream
import fs2.timeseries.{TimeStamped, TimeSeries}
import scodec.bits.ByteVector

def withBitrate[F[_]](input: Stream[F, TimeStamped[Option[ByteVector]]]): Stream[F, TimeStamped[Either[Long, Option[ByteVector]]]] =
  TimeStamped.withPerSecondRate[Option[ByteVector], Long](_.map(_.size).getOrElse(0L) * 8).toPipe(input)
```

We then need to adjust `withReceivedBitrate` to convert our source stream to a time series:

```scala
import scala.concurrent.duration._
import cats.effect.Temporal

def withReceivedBitrate[F[_]: Temporal](input: Stream[F, Byte]): Stream[F, TimeStamped[Either[Long, Option[ByteVector]]]] =
  TimeSeries.timePulled(input.chunks.map(_.toByteVector), 1.second, 1.second).through(withBitrate)
```

We've used the `timePulled` operation to build a time series out of a regular stream -- as the name indicates, it time stamps each received value with the wall clock time it was pulled from the source stream. It also has a configurable tick period - in this case, we set it to one second.

The remaining parts of our bitrate computation are unimpacted by this conversion to a time series (besides some slight signature changes).

## Scans

In the previous section, we wrote various stream transformations which incrementally built up a pipe that monitored the bitrate flowing through a stream. Each of these transformations, up until the final storage of the computed bitrate in a ref, were pure tranformations -- no effects were evaluated and no resources were opened. This type of processing is very common when working with time series.

The `fs2.Scan` type allows us to express these types of pure, stateful computations in a way which gives us a quite a bit more compositionality than `Pipe`. Rewriting our above example using `Scan` gives us the following:

```scala
import fs2.{Stream, Chunk, Scan}
import fs2.timeseries.{TimeStamped, TimeSeries}
import cats.effect.{Ref, Temporal}
import scodec.bits.ByteVector
import scala.collection.immutable.Queue
import scala.concurrent.duration._

def bitrate =
  TimeStamped.withPerSecondRate[Option[ByteVector], Long](_.map(_.size).getOrElse(0L) * 8)

def averageBitrate =
  bitrate.andThen(Scan.stateful1(Queue.empty[Long]) { 
    case (q, tsv @ TimeStamped(_, Right(_))) => (q, tsv)
    case (q, TimeStamped(t, Left(sample))) => 
      val q2 = (sample +: q).take(10)
      val average = q2.sum / q2.size
      (q, TimeStamped(t, Left(average)))
  })

def measureAverageBitrate[F[_]: Temporal](store: Ref[F, Long], input: Stream[F, Byte]): Stream[F, Byte] =
  TimeSeries.timePulled(input.chunks.map(_.toByteVector), 1.second, 1.second)
    .through(averageBitrate.toPipe)
    .flatMap {
      case TimeStamped(_, Left(bitrate)) => Stream.exec(store.set(bitrate))
      case TimeStamped(_, Right(Some(bytes))) => Stream.chunk(Chunk.byteVector(bytes))
      case TimeStamped(_, Right(None)) => Stream.empty
    }
```

In this example, both `bitrate` and `averageBitrate` are instances of `Scan`. The `averageBitrate` function is able to directly reuse `bitrate` via `andThen` to construct a "bigger" scan. After we've finished composing scans, we convert the scan to a pipe and apply it to the input stream (in `measureAverageBitrate`).

There are lots of combinators on `TimeStamped` and `TimeSeries` which provide scans. The `Scan` type also provides many generic combinators.