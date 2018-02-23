---
layout: page
title:  "Concurrency Primitives"
section: "concurrency-primitives"
position: 2
---

# Concurrency Primitives

In the [`fs2.async` package object](https://github.com/functional-streams-for-scala/fs2/blob/series/0.10/core/shared/src/main/scala/fs2/async/async.scala) you'll find a bunch of useful concurrency primitives:

- `Ref[F, A]`
- `Promise[F, A]`
- `Queue[F, A]`
- `Topic[F, A]`
- `Signal[F, A]`
- `Semaphore[F]`

These data structures could be very handy in a few cases. See below examples of each of them originally taken from [gvolpe examples](https://github.com/gvolpe/advanced-http4s/tree/master/src/main/scala/com/github/gvolpe/fs2):

### Concurrent Counter

This is probably one of the most common uses of the primitive `fs2.async.Ref[F, A]`.

The workers will concurrently run and modify the value of the Ref so this is one possible outcome showing "#worker >> currentCount":

```
#1 >> 0
#3 >> 0
#2 >> 0
#1 >> 2
#2 >> 3
#3 >> 3
```

```tut:silent
import cats.effect.{Effect, IO}
import fs2.StreamApp.ExitCode
import fs2.async.Ref
import fs2.{Scheduler, Sink, Stream, StreamApp, async}

import scala.concurrent.ExecutionContext.Implicits.global

class Worker[F[_]](number: Int, ref: Ref[F, Int])(implicit F: Effect[F]) {

  private val sink: Sink[F, Int] = _.evalMap(n => F.delay(println(s"#$number >> $n")))

  def start: Stream[F, Unit] =
    for {
      _ <- Stream.eval(ref.get).to(sink)
      _ <- Stream.eval(ref.modify(_ + 1))
      _ <- Stream.eval(ref.get).to(sink)
    } yield ()

}

class Counter[F[_] : Effect] extends StreamApp[F] {

  override def stream(args: List[String], requestShutdown: F[Unit]): fs2.Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        ref <- Stream.eval(async.refOf[F, Int](0))
        w1  = new Worker[F](1, ref)
        w2  = new Worker[F](2, ref)
        w3  = new Worker[F](3, ref)
        ec  <- Stream(w1.start, w2.start, w3.start).join(3).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
```

### Only Once

Whenever you are in a scenario when many processes can modify the same value but you only care about the first one in doing so and stop processing, then this is the use case of a `fs2.async.Promise[F, A]`.

Two processes will try to complete the promise at the same time but only one will succeed, completing the promise exactly once. The loser one will raise an error when trying to complete a promise already completed, that's why we call `attempt` on the evaluation.

Notice that the loser process will remain running in the background and the program will end on completion of all of the inner streams.

So it's a "race" in the sense that both processes will try to complete the promise at the same time but conceptually is different from "race". So for example, if you schedule one of the processes to run in 10 seconds from now, then the entire program will finish after 10 seconds and you can know for sure that the process completing the promise is going to be the first one.

```tut:silent
import cats.effect.{Effect, IO}
import fs2.StreamApp.ExitCode
import fs2.async.Promise
import fs2.{Scheduler, Stream, StreamApp, async}

import scala.concurrent.ExecutionContext.Implicits.global

class ConcurrentCompletion[F[_]](p: Promise[F, Int])(implicit F: Effect[F]) {

  private def attemptPromiseCompletion(n: Int): Stream[F, Unit] =
    Stream.eval(p.complete(n)).attempt.drain

  def start: Stream[F, ExitCode] =
    Stream(
      attemptPromiseCompletion(1),
      attemptPromiseCompletion(2),
      Stream.eval(p.get).evalMap(n => F.delay(println(s"Result: $n")))
    ).join(3).drain ++ Stream.emit(ExitCode.Success)

}

class Once[F[_]: Effect] extends StreamApp[F] {

  override def stream(args: List[String], requestShutdown: F[Unit]): fs2.Stream[F, ExitCode] =
    Scheduler(corePoolSize = 4).flatMap { implicit scheduler =>
      for {
        p <- Stream.eval(async.promise[F, Int])
        e <- new ConcurrentCompletion[F](p).start
      } yield e
    }

}
```

### FIFO (First IN / First OUT)

A typical use case of a `fs2.async.mutable.Queue[F, A]`, also quite useful to communicate with the external word as shown in the [guide](guide#talking-to-the-external-world).

q1 has a buffer size of 1 while q2 has a buffer size of 100 so you will notice the buffering when  pulling elements out of the q2.

```tut:silent
import cats.effect.{Effect, IO}
import fs2.StreamApp.ExitCode
import fs2.async.mutable.Queue
import fs2.{Scheduler, Stream, StreamApp, async}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class Buffering[F[_]](q1: Queue[F, Int], q2: Queue[F, Int])(implicit F: Effect[F]) {

  def start: Stream[F, Unit] =
    Stream(
      Stream.range(0, 1000).covary[F].to(q1.enqueue),
      q1.dequeue.to(q2.enqueue),
      //.map won't work here as you're trying to map a pure value with a side effect. Use `evalMap` instead.
      q2.dequeue.evalMap(n => F.delay(println(s"Pulling out $n from Queue #2")))
    ).join(3)

}

class Fifo[F[_]: Effect] extends StreamApp[F] {

  override def stream(args: List[String], requestShutdown: F[Unit]): fs2.Stream[F, ExitCode] =
    Scheduler(corePoolSize = 4).flatMap { implicit S =>
      for {
        q1 <- Stream.eval(async.boundedQueue[F, Int](1))
        q2 <- Stream.eval(async.boundedQueue[F, Int](100))
        bp = new Buffering[F](q1, q2)
        ec <- S.delay(Stream.emit(ExitCode.Success).covary[F], 5.seconds) concurrently bp.start.drain
      } yield ec
    }

}
```

### Single Publisher / Multiple Subscriber

Having a Kafka like system built on top of concurrency primitives is possible by making use of `fs2.async.mutable.Topic[F, A]`. In this more complex example, we will also show you how to make use of a `fs2.async.mutable.Signal[F, A]` to interrupt a scheduled Stream.

The program ends after 15 seconds when the signal interrupts the publishing of more events given that the final streaming merge halts on the end of its left stream (the publisher).

```
- Subscriber #1 should receive 15 events + the initial empty event
- Subscriber #2 should receive 10 events
- Subscriber #3 should receive 5 events
```

```scala
import cats.effect.{Effect, IO}
import fs2.StreamApp.ExitCode
import fs2.async.mutable.{Signal, Topic}
import fs2.{Scheduler, Sink, Stream, StreamApp, async}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

case class Event(value: String)

class EventService[F[_]](eventsTopic: Topic[F, Event],
                         interrupter: Signal[F, Boolean])(implicit F: Effect[F], S: Scheduler) {

  // Publishing events every one second until signaling interruption
  def startPublisher: Stream[F, Unit] =
    S.awakeEvery(1.second).flatMap { _ =>
      val event = Event(System.currentTimeMillis().toString)
      Stream.eval(eventsTopic.publish1(event))
    }.interruptWhen(interrupter)

  // Creating 3 subscribers in a different period of time and join them to run concurrently
  def startSubscribers: Stream[F, Unit] = {
    val s1: Stream[F, Event] = eventsTopic.subscribe(10)
    val s2: Stream[F, Event] = S.delay(eventsTopic.subscribe(10), 5.seconds)
    val s3: Stream[F, Event] = S.delay(eventsTopic.subscribe(10), 10.seconds)

    def sink(subscriberNumber: Int): Sink[F, Event] =
      _.evalMap(e => F.delay(println(s"Subscriber #$subscriberNumber processing event: $e")))

    Stream(s1.to(sink(1)), s2.to(sink(2)), s3.to(sink(3))).join(3)
  }

}

class PubSub[F[_]: Effect] extends StreamApp[F] {

  override def stream(args: List[String], requestShutdown: F[Unit]): fs2.Stream[F, ExitCode] =
    Scheduler(corePoolSize = 4).flatMap { implicit S =>
      for {
        topic     <- Stream.eval(async.topic[F, Event](Event("")))
        signal    <- Stream.eval(async.signalOf[F, Boolean](false))
        service   = new EventService[F](topic, signal)
        exitCode  <- Stream(
                      S.delay(Stream.eval(signal.set(true)), 15.seconds),
                      service.startPublisher.concurrently(service.startSubscribers)
                    ).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield exitCode
    }

}
```

### Shared Resource

When multiple processes try to access a precious resource you might want to constraint the number of accesses. Here is where `fs2.async.mutable.Semaphore[F]` comes in useful.

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
import cats.effect.{Effect, IO}
import cats.syntax.functor._
import fs2.StreamApp.ExitCode
import fs2.async.mutable.Semaphore
import fs2.{Scheduler, Stream, StreamApp, async}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class PreciousResource[F[_]: Effect](name: String, s: Semaphore[F])(implicit S: Scheduler) {

  def use: Stream[F, Unit] =
    for {
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Availability: $a")))
      _ <- Stream.eval(s.decrement)
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Started | Availability: $a")))
      _ <- S.sleep(3.seconds)
      _ <- Stream.eval(s.increment)
      _ <- Stream.eval(s.available.map(a => println(s"$name >> Done | Availability: $a")))
    } yield ()

}

class Resources[F[_]: Effect] extends StreamApp[F] {

  override def stream(args: List[String], requestShutdown: F[Unit]): fs2.Stream[F, ExitCode] =
    Scheduler(corePoolSize = 4).flatMap { implicit scheduler =>
      for {
        s   <- Stream.eval(async.semaphore[F](1))
        r1  = new PreciousResource[F]("R1", s)
        r2  = new PreciousResource[F]("R2", s)
        r3  = new PreciousResource[F]("R3", s)
        ec  <- Stream(r1.use, r2.use, r3.use).join(3).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
```
