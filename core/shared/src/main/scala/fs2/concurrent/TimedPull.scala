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
//package concurrent

import fs2.concurrent.SignallingRef
import scala.concurrent.duration._
import fs2.internal.Token
import cats.effect._
import cats.syntax.all._

object tp {

  trait TimedPull[F[_], A] {
    type Timeout
    def uncons: Pull[F, INothing, Option[(Either[Timeout, Chunk[A]], TimedPull[F, A])]]
    def startTimer(t: FiniteDuration): Pull[F, INothing, Unit]
  }
  object TimedPull {
    def go[F[_]: Temporal, A, B](pull: TimedPull[F, A] => Pull[F, B, Unit]): Pipe[F, A, B] = { source =>
      def now = Temporal[F].monotonic

      class Timeout(val id: Token, issuedAt: FiniteDuration, d: FiniteDuration) {
        def asOfNow:  F[FiniteDuration] = now.map(now => d - (now - issuedAt))
      }

      def newTimeout(d: FiniteDuration): F[Timeout] =
        (Token[F], now).mapN(new Timeout(_, _, d))

      def put(s: String) = Concurrent[F].unit.map(_ => println(s))

      Stream.eval(newTimeout(0.millis).flatTap(x => put(x.toString)).mproduct(SignallingRef[F, Timeout])).flatMap { case (initial, time) =>
        def nextAfter(t: Timeout): Stream[F, Timeout] =
          time.discrete.dropWhile(_.id == t.id).head



        // TODO is the initial time.get.unNone fine or does it spin?
        def timeouts: Stream[F, Token] =
          Stream.eval(put("yo") >> time.get.flatTap(x => put(x.toString))).flatMap { timeout =>
            Stream.eval(timeout.asOfNow).flatMap { t =>
              if (t <= 0.nanos) Stream.eval_(put("a")) ++ Stream.emit(timeout.id) ++ nextAfter(timeout).drain
              else Stream.eval_(put("b"))  ++ Stream.sleep_[F](t)
            }
          } ++ timeouts

        def output: Stream[F, Either[Token, Chunk[A]]] =
          (nextAfter(initial).drain ++ timeouts)
            .map(_.asLeft)
            .mergeHaltR(source.chunks.map(_.asRight))
            .flatMap {
              case chunk @ Right(_) => Stream.emit(chunk)
              case timeout @ Left(id) =>
                Stream
                  .eval(time.get)
                  .collect { case currentTimeout if currentTimeout.id == id => timeout }
                  .evalTap(x => put(x.toString))
            }

        def toTimedPull(s: Stream[F, Either[Token, Chunk[A]]]): TimedPull[F, A] = new TimedPull[F, A] {
          type Timeout = Token

          def uncons: Pull[F, INothing, Option[(Either[Token, Chunk[A]], TimedPull[F, A])]] =
            s.pull.uncons1
              .map( _.map { case (r, next) => r -> toTimedPull(next) })

          def startTimer(t: FiniteDuration): Pull[F, INothing, Unit] = Pull.eval {
            newTimeout(t).flatMap(time.set)
          }
        }

        pull(toTimedPull(output)).stream
      }
    }
  }

  import cats.effect.unsafe.implicits.global

  def t = {
    Stream.range(1, 50)
      .unchunk
      .covary[IO]
      .metered(100.millis)
      .through {
        TimedPull.go[IO, Int, Int] { tp =>
          def go(tp: TimedPull[IO, Int]): Pull[IO, Int, Unit] =
            tp.uncons.flatMap {
              case None => Pull.done
              case Some((Right(c), n)) => Pull.output(c) >> go(n)
              case Some((_, n)) => go(n)
            }

          //tp.startTimer(500.millis) >>
          go(tp)
        }
      }.compile.drain.unsafeRunSync()
  }

  def tt = {
        val s = Stream.sleep[IO](1.second).as(1).evalMap(x => IO(println(x))) ++ Stream.sleep[IO](1.second).as(2).evalMap(x => IO(println(x))) ++ Stream.never[IO]

    s.pull.timed { tp =>
      tp.startTimer(900.millis) >>
      tp.startTimer(1.second) >>
      Pull.eval(IO.sleep(800.millis)) >>
      // tp.startTimer(301.millis) >>
      tp.uncons.flatMap {
        case Some((Right(_), next)) =>
          next.uncons.flatMap {
            case Some((Left(_), _)) => Pull.done
            case _ =>
              Pull.raiseError[IO](new Exception(s"Expected timeout second, received element"))
          }
        case _ =>
          Pull.raiseError[IO](new Exception(s"Expected element first, received timeout"))
      }
    }.stream
      .compile
      .drain
  }.unsafeRunSync()


  def ttt =
    Stream
      .range(1, 10)
      .covary[IO]
      .evalTap(i => IO(println(i)))
      .switchMap(_ => Stream.sleep[IO](0.millis))
      //.switchMap(Stream.emit)
      .evalTap(i => IO(println(i)))
      .interruptAfter(5.seconds)
      .compile
      .drain
      .unsafeRunSync()


  def tttt =
    Stream.range(1, 50).covary[IO].metered(100.millis).hold(0).flatMap { s =>
      s.discrete.dropWhile(_ == 0).switchMap(a => Stream.sleep[IO](0.millis).as(a)).showLinesStdOut
    }.interruptAfter(5.seconds).compile.drain.unsafeRunSync
  
  // problematic interleaving
  //  go is iterating and accumulating
  //  the stream enqueues a chunk
  //  the timeout enqueues
  //  go pulls a chunk
  //  go reaches limit, resets and starts another timeout, then recurs
  //  go receives stale timeout, needs to be able to discard it
  //  the state tracking the current timeout needs to be set before the next iteration of go

  // if we express timedUncons and startTimer as `F`, that guarantee is hard to achieve
  // cause they could be concurrent. We could express both as Pulls, forcing a sync loop.
  // need to add a note that the `unconsTimed` `Pull`, unlike `uncons`, is not idempotent

  // A separate concern would be to guarantee true pull-based semantics vs push-based with backpressure at 1
  // def prog =
  //   (MiniKafka.create[F], Queue.synchronous[F, Unit], SignallingRef[F, Unit](())).mapN {
  //     (kafka, q, sig) =>
  //     def consumer = Stream.repeatEval(sig.set(()) >> q.dequeue1).evalMap(_ => kafka.commit)
  //     def producer = sig.discrete.zipRight(Stream.repeatEval(kafka.poll)).through(q.enqueue)

  //     consumer
  //       .concurrently(producer)
  //       .compile
  //       .drain
  //   }.flatten

}
