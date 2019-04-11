---
layout: page
title:  "Concurrency Primitives"
section: "concurrency-primitives"
position: 2
---

# Concurrency Primitives

In the [`fs2.concurrent` package](https://github.com/functional-streams-for-scala/fs2/blob/series/1.0/core/shared/src/main/scala/fs2/concurrent/) you'll find a bunch of useful concurrency primitives built on the concurrency primitives defined in `cats.effect.concurrent`:

- `Queue[F, A]`
- `Topic[F, A]`
- `Signal[F, A]`

These data structures could be very handy in a few cases. See below examples of each of them originally taken from [gvolpe examples](https://github.com/gvolpe/advanced-http4s/tree/master/src/main/scala/com/github/gvolpe/fs2):

### FIFO (First IN / First OUT)

A typical use case of a `fs2.concurrent.Queue[F, A]`, also quite useful to communicate with the external world as shown in the [guide](guide#talking-to-the-external-world).

q1 has a buffer size of 1 while q2 has a buffer size of 100 so you will notice the buffering when  pulling elements out of the q2.

```tut:silent
import cats.implicits._
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Timer}
import fs2.concurrent.Queue
import fs2.Stream

import scala.concurrent.duration._

class Buffering[F[_]](q1: Queue[F, Int], q2: Queue[F, Int])(implicit F: Concurrent[F]) {

  def start: Stream[F, Unit] =
    Stream(
      Stream.range(0, 1000).covary[F].through(q1.enqueue),
      q1.dequeue.through(q2.enqueue),
      //.map won't work here as you're trying to map a pure value with a side effect. Use `evalMap` instead.
      q2.dequeue.evalMap(n => F.delay(println(s"Pulling out $n from Queue #2")))
    ).parJoin(3)
}

object Fifo extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val stream = for {
      q1 <- Stream.eval(Queue.bounded[IO, Int](1))
      q2 <- Stream.eval(Queue.bounded[IO, Int](100))
      bp = new Buffering[IO](q1, q2)
      _  <- Stream.sleep_[IO](5.seconds) concurrently bp.start.drain
    } yield ()
    stream.compile.drain.as(ExitCode.Success)
  }
}
```

### Single Publisher / Multiple Subscriber

Having a Kafka like system built on top of concurrency primitives is possible by making use of `fs2.concurrent.Topic[F, A]`. In this more complex example, we will also show you how to make use of a `fs2.concurrent.Signal[F, A]` to interrupt a scheduled Stream.

The program ends after 15 seconds when the signal interrupts the publishing of more events given that the final streaming merge halts on the end of its left stream (the publisher).

```
- Subscriber #1 should receive 15 events + the initial empty event
- Subscriber #2 should receive 10 events
- Subscriber #3 should receive 5 events
- Publisher sends Quit event
- Subscribers raise interrupt signal on Quit event
```

```scala
import scala.concurrent.duration._
import scala.language.higherKinds
import java.util.concurrent.TimeUnit
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Timer}
import cats.syntax.all._
import fs2.{Pipe, Stream}
import fs2.concurrent.{SignallingRef, Topic}

sealed trait Event
case class Text(value: String) extends Event
case object Quit extends Event

class EventService[F[_]](eventsTopic: Topic[F, Event], interrupter: SignallingRef[F, Boolean])(
  implicit F: Concurrent[F],
  timer: Timer[F]
) {

  // Publishing 15 text events, then single Quit event, and still publishing text events
  def startPublisher: Stream[F, Unit] = {
    val textEvents = eventsTopic.publish(
      Stream.awakeEvery[F](1.second)
        .zipRight(Stream.eval(timer.clock.realTime(TimeUnit.MILLISECONDS).map(t => Text(t.toString))).repeat)
    )
  
    val quitEvent = Stream.eval(eventsTopic.publish1(Quit))

    (textEvents.take(15) ++ quitEvent ++ textEvents).interruptWhen(interrupter)
  }

  // Creating 3 subscribers in a different period of time and join them to run concurrently
  def startSubscribers: Stream[F, Unit] = {
    def processEvent(subscriberNumber: Int): Pipe[F, Event, Unit] =
      _.flatMap {
        case e @ Text(_) =>
           Stream.eval(F.delay(println(s"Subscriber #$subscriberNumber processing event: $e")))
       case Quit => Stream.eval(interrupter.set(true))
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

object PubSub extends IOApp {

  val program = for {
    topic <- Stream.eval(Topic[IO, Event](Text("Initial Event")))
    signal <- Stream.eval(SignallingRef[IO, Boolean](false))
    service = new EventService[IO](topic, signal)
    _ <- service.startPublisher.concurrently(service.startSubscribers)
  } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    program.compile.drain.as(ExitCode.Success)
  }
}
```

### Shared Resource

When multiple processes try to access a precious resource you might want to constraint the number of accesses. Here is where `cats.effect.concurrent.Semaphore[F]` comes in useful.

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

```tut:silent
import cats.effect.{Concurrent, ExitCode, IO, IOApp}
import cats.effect.concurrent.Semaphore
import cats.syntax.functor._
import fs2.Stream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class PreciousResource[F[_]: Concurrent: Timer](name: String, s: Semaphore[F]) {

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

object Resources extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val stream = for {
      s   <- Stream.eval(Semaphore[IO](1))
      r1  = new PreciousResource[IO]("R1", s)
      r2  = new PreciousResource[IO]("R2", s)
      r3  = new PreciousResource[IO]("R3", s)
      _   <- Stream(r1.use, r2.use, r3.use).parJoin(3).drain
    } yield ()
    stream.compile.drain.as(ExitCode.Success)
  }
}
```
