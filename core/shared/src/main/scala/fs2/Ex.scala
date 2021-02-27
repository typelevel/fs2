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

import cats.effect._
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect.std.{CyclicBarrier, CountDownLatch}
import fs2.concurrent.Topic

// scenarios to build broacastThrough on top of Topic
// specifically making sure all pipes receive every element
object Ex {

  def sleepRandom = IO(scala.util.Random.nextInt(1000).millis).flatMap(IO.sleep)

  def res(name: String) =
    Resource.make(
      sleepRandom >> IO.println(s"opening $name")
    )(_ => IO.println(s"closing $name"))
  .as {
        Stream.eval(IO.println(s"starting $name"))  ++ Stream.eval(sleepRandom) ++ Stream.eval(IO.println(s"$name executed"))
      }

  // problem:
  //  you want all the subscriptions opened before any subscriber starts,
  //  but close them timely

    /////////////////////////
  // Technique number 1, deferred + append
  // works, and should behave properly with interruption too (conceptually)
  def a =
    Stream.eval(IO.deferred[Unit]).flatMap { wait =>
      Stream
        .range(0, 6)
        .covary[IO]
        .foldMap(i => Stream.resource(res(i.toString)))
        .flatMap(
          _
            .append(Stream.exec(wait.complete(()).void))
            .map(x => Stream.exec(wait.get) ++ x)
            .parJoinUnbounded
        )
    }.compile
     .drain
     .unsafeRunSync()

  // scala> Ex.aplus
  // opening 0
  // opening 1
  // opening 2
  // opening 3
  // opening 4
  // opening 5
  // 3 executed
  // closing 3
  // 1 executed
  // closing 1
  // 5 executed
  // closing 5
  // 0 executed
  // closing 0
  // 2 executed
  // closing 2
  // 4 executed
  // closing 4


  // works, tests the scenario where one of the pipes interrupts the subscription
  def aa =
    Stream.eval(IO.ref(0)).flatMap { count =>
      Stream.eval(IO.deferred[Unit]).flatMap { wait =>
        Stream
          .range(0, 6)
          .covary[IO]
          .foldMap(i => Stream.resource(res(i.toString)))
          .flatMap(
            _
              .append(Stream.exec(wait.complete(()).void))
              .map { x =>
                val y = Stream.exec(wait.get) ++ x
                Stream.eval(count.updateAndGet(_ + 1)).flatMap { c =>
                  // simulates a pipe that interrupts one of the subs immediately
                  if (c == 2) y.interruptAfter(30.millis)
                  else y
                }
            }.parJoinUnbounded
          )
      }
    }
      .compile
      .drain
      .unsafeRunSync()

  // external interruption, testing shutdown
  // leaks a single resource (independent bug I think)
  def aaa =
    Stream.eval(IO.deferred[Unit]).flatMap { wait =>
      Stream
        .range(0, 6)
        .covary[IO]
        .foldMap(i => Stream.resource(res(i.toString)))
        .flatMap(
          _
            .append(Stream.exec(wait.complete(()).void))
            .map(x => Stream.exec(wait.get) ++ x)
            .parJoinUnbounded
        )
    }.interruptAfter(1.second)
      .compile
      .drain
      .unsafeRunSync()


  ////////////////////////////////////////////
  // Technique no 2, Barrier
  // works, should work properly with interruption
    def b =
    Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
      Stream.range(0, 6).map { i =>
        Stream
          .resource(res(i.toString))
          .flatMap { sub =>
            def pipe[A]: Pipe[IO, A, A] = x => x

            // crucial that awaiting on the barrier
            // is not passed to the pipe, so that the pipe cannot
            // interrupt it
            Stream.eval(barrier.await) ++ sub.through(pipe)
          }
      }.parJoinUnbounded
    }.compile.drain.unsafeRunSync()

  // works, internal interruption (the pipe interrupts)
  def bb =
    Stream.eval(IO.ref(0)).flatMap { count =>
      Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
        Stream.range(0, 6).map { i =>
          Stream
            .resource(res(i.toString))
            .flatMap { sub =>
              def pipe[A]: Pipe[IO, A, A] =  {x =>
                Stream.eval(count.updateAndGet(_ + 1)).flatMap { c =>
                  // simulates a pipe that interrupts one of the subs immediately
                  if (c == 2) x.interruptAfter(30.millis)
                  else x
                }
              }
              // crucial that awaiting on the barrier
              // is not passed to the pipe, so that the pipe cannot
              // interrupt it
              Stream.eval(barrier.await) ++ sub.through(pipe)
            }
        }.parJoinUnbounded
      }
    } .compile.drain.unsafeRunSync()


  // suffers from the same bug as aplusII.
  // Not a big deal for `broadcastThrough`, but needs investigation
  def bbb =
    Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
      Stream.range(0, 6).map { i =>
        Stream
          .resource(res(i.toString))
          .flatMap { sub =>
            def pipe[A]: Pipe[IO, A, A] = x => x

            // crucial that awaiting on the barrier
            // is not passed to the pipe, so that the pipe cannot
            // interrupt it
            Stream.eval(barrier.await) ++ sub.through(pipe)
          }
      }.parJoinUnbounded
    }.interruptAfter(30.millis).compile.drain.unsafeRunSync()


  ///// found bugs & problems


  // Problem 1, IO.canceled causes deadlock
  def p1 = Stream.range(0, 3).covary[IO].mapAsyncUnordered(100) { i =>
    if (i == 2) IO.canceled.onCancel(IO.println("cancelled"))
    else IO.sleep(500.millis) >> IO.println(i)
  }.compile.drain.unsafeRunSync()
  // cancelled
  // 0
  // 1
  // hangs


  // problem 2, finalizer doesn't get called with Stream.resource
  def p2 = {
    def open = IO.sleep(1.second) >> IO.println("open")
    def close = (_: Unit) => IO.println("close")
    def use = (_: Unit) => IO.println("use")

    IO.println("Example with Stream.bracket") >>
    Stream
      .bracket(open)(close)
      .evalMap(use)
      .interruptAfter(200.millis)
      .compile.drain >>
    IO.println("Example with Stream.resource") >>
    Stream
      .resource(Resource.make(open)(close))
      .evalMap(use)
      .interruptAfter(200.millis)
      .compile.drain
  }.unsafeRunSync()
  // Example with Stream.bracket
  // open
  // close
  // Example with Stream.resource
  // open


  // def e =
  //   Stream
  //     .range(0, 15)
  //     .covary[IO]
  //     .metered(1.second)
  //     .debug(v => s"In $v")
  //     .interruptAfter(5.seconds)
  //     .broadcastThrough(
  //       (_: Stream[IO, Int]).metered(2.seconds).debug(v => s"A: $v"),
  //       (_: Stream[IO, Int]).metered(2.seconds).debug(v => s"B: $v"),
  //     )
  //     .compile.drain.unsafeRunSync()

  def broadcastThrough[A, B](
    pipes: Pipe[IO, A, B]*
  ): Pipe[IO, A, B] = { in =>
    Stream.eval {
      (
        CountDownLatch[IO](pipes.length),
        fs2.concurrent.Topic[IO, Option[Chunk[A]]]
      ).tupled
    }.flatMap { case (latch, topic) =>
        Stream(pipes: _*)
          .map { pipe =>
            Stream.resource(topic.subscribeAwait(1))
              .flatMap { sub =>
                // crucial that awaiting on the latch is not passed to
                // the pipe, so that the pipe cannot interrupt it and alter
                // the latch count
                Stream.exec(latch.release >> latch.await) ++
                sub.unNoneTerminate.flatMap(Stream.chunk).through(pipe)
              }
          }
          .parJoinUnbounded
          .concurrently  {
            Stream.eval(latch.await) ++
            in.chunks.noneTerminate.through(topic.publish)
          }
    }
  }

  def o =
    Stream
      .range(0, 10)
      .covary[IO]
      .through(
        broadcastThrough[Int, Int](
          _.filter(_ % 2 == 0).debug(v => s"even $v"),
          _.filter(_ % 2 != 0).debug(v => s"odd $v"),
        )
      ).interruptAfter(2.seconds).compile.drain.unsafeRunSync()

  def e = {
    IO.uncancelable { poll =>

      @volatile var res: Option[Int] = None // new java.util.concurrent.atomic.AtomicReference[Option[Int]]

      // can't exploit that they're both `IO`, `F` and `G` reality
      val action = IO.uncancelable { poll =>
        (IO.sleep(2.seconds).onCancel(IO.println("cancelled")).flatTap(_ => IO.println("I made it")).as(1)) // user
          .map { r =>
            //res.set(r.some)
            res = r.some
            println(s"value of ${res.get}")
          }

      }.onCancel(IO.println("eventually cancelled"))

      action.start.flatMap { fib =>
        poll(fib.joinWith(IO.canceled >> IO.never))
          .onCancel {
            fib.cancel >> IO.println("yooo") >> IO.unit.flatMap { _ =>
//              res.get
              res 

match {
                case None => IO.println("not propagated")
                case Some(r) => IO.println(s"$r still needs releasing")
              }
            }
          }
          .redeemWith(
            e => IO.println(e), // fail
            r => IO.println(s"register resource $r") // register resource
          )
      }

    }.timeoutTo(1.second, IO.unit)

  }.unsafeRunSync()

  def e2 = {
    IO.uncancelable { poll =>

      @volatile var res: Option[Int] = None // new java.util.concurrent.atomic.AtomicReference[Option[Int]]

      // can't exploit that they're both `IO`, `F` and `G` reality
      val action = IO.uncancelable { poll =>
        poll((IO.sleep(2.seconds)).onCancel(IO.println("cancelled")).flatTap(_ => IO.println("I made it")).as(1)) // user
          .map { r =>
            //res.set(r.some)
            res = r.some
            println(s"value of ${res.get}")
          }

      }.onCancel(IO.println("eventually cancelled"))

      action.start.flatMap { fib =>
        poll(fib.joinWith(IO.canceled >> IO.never))
          .onCancel {
            fib.cancel >> IO.println("yooo") >> IO.unit.flatMap { _ =>
//              res.get
              res 

match {
                case None => IO.println("action got cancelled")
                case Some(r) => IO.println(s"$r still needs releasing")
              }
            }
          }
          .redeemWith(
            e => IO.println(e), // fail
            r => IO.println(s"register resource $r") // register resource
          )
      }

    }.timeoutTo(1.second, IO.unit)

  }.unsafeRunSync()



}

