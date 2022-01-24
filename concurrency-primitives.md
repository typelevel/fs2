# Concurrency Primitives

In the [`fs2.concurrent` package](https://github.com/functional-streams-for-scala/fs2/blob/series/1.0/core/shared/src/main/scala/fs2/concurrent/) you'll find a bunch of useful concurrency primitives built on the concurrency primitives defined in `cats-effect`. For example:

- `Topic[F, A]`
- `Signal[F, A]`

In addition, `Stream` provides functions to interact with cats-effect's `Queue`. 

## Simple Examples

### Topic

(based on [Pera Villega](https://perevillega.com/)'s example [here](https://underscore.io/blog/posts/2018/03/20/fs2.html))

Topic implements the publish-subscribe pattern. In the following example, `publisher` and `subscriber` are started concurrently with the publisher continuously publishing the string `"1"` to the topic and the subscriber consuming it until it has received four elements.

```scala
import cats.effect._
import cats.effect.unsafe.implicits.global
import fs2.Stream
import fs2.concurrent.Topic

Topic[IO, String].flatMap { topic =>
  val publisher = Stream.constant("1").covary[IO].through(topic.publish)
  val subscriber = topic.subscribe(10).take(4)
  subscriber.concurrently(publisher).compile.toVector
}.unsafeRunSync()
```

### Signal

`Signal` can be used as a means of communication between two different streams. In the following example `s1` is a stream that emits the current time every second and is interrupted when the signal has value of `true`. `s2` is a stream that sleeps for four seconds before assigning `true` to the signal. This leads to `s1` being interrupted.

(Note that `SignallingRef` extends `Signal`)

```scala
import cats.effect._
import cats.effect.unsafe.implicits.global
import fs2.Stream
import fs2.concurrent.SignallingRef

import scala.concurrent.duration._

SignallingRef[IO, Boolean](false).flatMap { signal =>
  val s1 = Stream.awakeEvery[IO](1.second).interruptWhen(signal)
  val s2 = Stream.sleep[IO](4.seconds) >> Stream.eval(signal.set(true))
  s1.concurrently(s2).compile.toVector
}.unsafeRunSync()
```

### Queue

In the following example `Stream.fromQueueNoneTerminated` is used to create a stream from a `cats.effect.std.Queue`. This snippet returns `List(1,2,3)`: the stream will emit the first three values that the queue returns (`Some(1), Some(2), Some(3)`) and be interrupted afterwards, when the queue returns `None`.

```scala
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import fs2.Stream

val program = for {
  queue <- Queue.unbounded[IO, Option[Int]]
  streamFromQueue = Stream.fromQueueNoneTerminated(queue) // Stream is terminated when None is returned from queue
  _ <- Seq(Some(1), Some(2), Some(3), None).map(queue.offer).sequence
  result <- streamFromQueue.compile.toList
} yield result

program.unsafeRunSync()
```

## Advanced Examples

These data structures could be very handy in more complex cases. See below examples of each of them originally taken from [gvolpe examples](https://github.com/gvolpe/advanced-http4s/tree/master/src/main/scala/com/github/gvolpe/fs2):

### FIFO (First IN / First OUT)

A typical use case of a `cats.effect.std.Queue[F, A]`, also quite useful to communicate with the external world as shown in the [guide](guide.md#talking-to-the-external-world).

q1 has a buffer size of 1 while q2 has a buffer size of 100 so you will notice the buffering when  pulling elements out of the q2.

```scala
import cats.effect.{Concurrent, IO, IOApp}
import cats.effect.std.{Console, Queue}
import fs2.Stream

import scala.concurrent.duration._

class Buffering[F[_]: Concurrent: Console](q1: Queue[F, Int], q2: Queue[F, Int]) {

  def start: Stream[F, Unit] =
    Stream(
      Stream.range(0, 1000).covary[F].foreach(q1.offer),
      Stream.repeatEval(q1.take).foreach(q2.offer),
      //.map won't work here as you're trying to map a pure value with a side effect. Use `foreach` instead.
      Stream.repeatEval(q2.take).foreach(n => Console[F].println(s"Pulling out $n from Queue #2"))
    ).parJoin(3)
}

object Fifo extends IOApp.Simple {

  def run: IO[Unit] = {
    val stream = for {
      q1 <- Stream.eval(Queue.bounded[IO, Int](1))
      q2 <- Stream.eval(Queue.bounded[IO, Int](100))
      bp = new Buffering[IO](q1, q2)
      _  <- Stream.sleep[IO](5.seconds) concurrently bp.start.drain
    } yield ()
    stream.compile.drain
  }
}
```

### Single Publisher / Multiple Subscriber

Having a Kafka like system built on top of concurrency primitives is possible by making use of `fs2.concurrent.Topic[F, A]`. In this more complex example, we will also show you how to make use of a `fs2.concurrent.Signal[F, A]` to interrupt a scheduled Stream.

The program ends after 15 seconds when the signal interrupts the publishing of more events given that the final streaming merge halts on the end of its left stream (the publisher).

```
- Subscriber #1 should receive 15 events
- Subscriber #2 should receive 10 events
- Subscriber #3 should receive 5 events
- Publisher sends Quit event
- Subscribers raise interrupt signal on Quit event
```

```scala
import scala.concurrent.duration._
import scala.language.higherKinds
import cats.effect.{Concurrent, IO, IOApp}
import cats.syntax.all._
import fs2.{Pipe, Stream, INothing}
import fs2.concurrent.{SignallingRef, Topic}

sealed trait Event
case class Text(value: String) extends Event
case object Quit extends Event

class EventService[F[_]](eventsTopic: Topic[F, Event], interrupter: SignallingRef[F, Boolean])(
  implicit F: Temporal[F], console: Console[F]
) {

  // Publishing 15 text events, then single Quit event, and still publishing text events
  def startPublisher: Stream[F, Unit] = {
    val textEvents =
      Stream.awakeEvery[F](1.second)
        .zipRight(Stream.repeatEval(Clock[F].realTime.map(t => Text(t.toString))))

    val quitEvent = Stream.eval(eventsTopic.publish1(Quit).as(Quit))

    (textEvents.take(15) ++ quitEvent ++ textEvents)
      .through(eventsTopic.publish)
      .interruptWhen(interrupter)
  }

  // Creating 3 subscribers in a different period of time and join them to run concurrently
  def startSubscribers: Stream[F, Unit] = {
    def processEvent(subscriberNumber: Int): Pipe[F, Event, INothing] =
      _.foreach {
        case e @ Text(_) =>
           console.println(s"Subscriber #$subscriberNumber processing event: $e")
       case Quit => interrupter.set(true)
     }

    val events: Stream[F, Event] =
      eventsTopic.subscribe(10)

    Stream(
      events.through(processEvent(1)),
      events.delayBy(5.second).through(processEvent(2)),
      events.delayBy(10.second).through(processEvent(3))
    ).parJoin(3)
  }
}

object PubSub extends IOApp.Simple {

  val program = for {
    topic <- Stream.eval(Topic[IO, Event])
    signal <- Stream.eval(SignallingRef[IO, Boolean](false))
    service = new EventService[IO](topic, signal)
    _ <- service.startPublisher.concurrently(service.startSubscribers)
  } yield ()

  def run: IO[Unit] = program.compile.drain
}
```

### Shared Resource

When multiple processes try to access a precious resource you might want to constraint the number of accesses. Here is where `cats.effect.std.Semaphore[F]` comes in useful.

Three processes are trying to access a shared resource at the same time but only one at a time will be granted access and the next process have to wait until the resource gets available again (availability is one as indicated by the semaphore counter).

R1, R2 & R3 will request access of the precious resource concurrently so this could be one possible outcome:

```
R1 >> Availability: 1
R2 >> Availability: 1
R2 >> Started | Availability: 0
R3 >> Availability: 0
--------------------------------
R1 >> Started | Availability: 0
R2 >> Done | Availability: 0
--------------------------------
R3 >> Started | Availability: 0
R1 >> Done | Availability: 0
--------------------------------
R3 >> Done | Availability: 1
```

This means when R1 and R2 requested the availability it was one and R2 was faster in getting access to the resource so it started processing. R3 was the slowest and saw that there was no availability from the beginning.

Once R2 was done R1 started processing immediately showing no availability.
Once R1 was done R3 started processing immediately showing no availability.
Finally, R3 was done showing an availability of one once again.

```scala
import cats.effect.{IO, IOApp, Temporal}
import cats.effect.std.Semaphore
import cats.syntax.all._
import fs2.Stream

import scala.concurrent.duration._

class PreciousResource[F[_]: Temporal](name: String, s: Semaphore[F]) {

  def use: Stream[F, Unit] = {
    for {
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Availability: $a")))
      _ <- Stream.eval(s.acquire)
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Started | Availability: $a")))
      _ <- Stream.sleep(3.seconds)
      _ <- Stream.eval(s.release)
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Done | Availability: $a")))
    } yield ()
  }
}

object Resources extends IOApp.Simple {

  def run: IO[Unit] = {
    val stream = for {
      s   <- Stream.eval(Semaphore[IO](1))
      r1  = new PreciousResource[IO]("R1", s)
      r2  = new PreciousResource[IO]("R2", s)
      r3  = new PreciousResource[IO]("R3", s)
      _   <- Stream(r1.use, r2.use, r3.use).parJoin(3)
    } yield ()
    stream.compile.drain
  }
}
```
