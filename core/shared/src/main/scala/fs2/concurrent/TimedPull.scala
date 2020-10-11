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

  //TODO test case for stale timeout
  // source stream: wait 100millis then emit chunk than `never`
  // timed pull body:, start time 100 millis (to race with source) then, recursively
  // if left, record that
  // if right/ record that and start a very long timeout (to reset it but not get it)
  // interrupt resulting stream after some time, but not as long as the second timeout
  //  good outcomes:
  //   recorded chunk only
  //   recorded timeout and then chunk
  //  bug to repro if check is eliminated
  //   recorded chunk and then timeout: since the new timeout is far in the future,
  //   it means we received a stale timeout, which breaks the invariant that an old
  //   timeout can never be received after startTimer is called


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

      Stream.eval(SignallingRef[F, Option[Timeout]](None)).flatMap { time =>
        def nextAfter(t: Timeout): Stream[F, Timeout] =
          time.discrete.unNone.dropWhile(_.id == t.id).head

        // TODO is the initial time.get.unNone fine or does it spin?
        def timeouts: Stream[F, Token] =
          Stream.eval(time.get).unNone.flatMap { timeout =>
            Stream.eval(timeout.asOfNow).flatMap { t =>
              if (t <= 0.nanos) Stream.emit(timeout.id) ++ nextAfter(timeout).drain
              else Stream.sleep_[F](t)
            }
          } ++ timeouts

        def output: Stream[F, Either[Token, Chunk[A]]] =
          timeouts
            .map(_.asLeft)
            .mergeHaltR(source.chunks.map(_.asRight))
            .evalMapFilter { // TODO filter of stale timeouts in uncons

              case Right(c) => c.asRight[Token].some.pure[F]
              case Left(id) => time.get.map(t => t.filter(_.id != id).as(id.asLeft[Chunk[A]]))
            }

        def toTimedPull(s: Stream[F, Either[Token, Chunk[A]]]): TimedPull[F, A] = new TimedPull[F, A] {
          type Timeout = Token

          def uncons: Pull[F, INothing, Option[(Either[Token, Chunk[A]], TimedPull[F, A])]] =
            s.pull.uncons1
              .map(_.map(_.map(toTimedPull)))

          def startTimer(t: FiniteDuration): Pull[F, INothing, Unit] = Pull.eval {
            newTimeout(t).flatMap(t => time.set(t.some))
          }
        }

        pull(toTimedPull(output)).stream
      }
    }
  }

  // TODO both old and new impl probably have a corner case with timing out on the first chunk
  // think about that
  // Actually it doesn't affect only the first chunk, but every time there has been a timeout
  // but no chunks currently emitted (empty acc)

  // so let's say we have groupWithin(10, 1.second). A stream emits 10
  // elems, so groupWithin emits those and restarts go, with an empty
  // Chunk queue. Then the stream sleeps 1 second, groupWithin hits the
  // timeout, emits nothing, and resets the timer. The stream sleep 200
  // mills, and emits 3 elems. In the current implementation, that
  // something is not emitted, because the timer has been resetted (and
  // is not at 800 mills). But from the persective of the returned
  // Stream, we have been waiting for an element for 1.2 seconds, and
  // even though 3 elems have been emitted by the original stream, those
  // are not emitted in the result
  //
  //  A simpler case of this problem would be a timeout before the first
  // chunk of the stream arrives: again say the stream sleeps 1.2 seconds
  // on then emits 3 elems, the current implementation will wait another
  // 800 millis before emitting those 3, whereas arguably they should be
  // emitted immediately
  //
  // a solution would be to go in another recursive state, has timed out,
  // which emits the next chunk as soon as it arrives, and resets the timer then.
  // the question is what to do if the chunk which arrives after the limit is bigger
  // than the size: do we emit all of it (after splitting), or do we accumulate the remainder
  // for the next chunk?

  def groupWithin[O](s: Stream[IO, O], n: Int, t: FiniteDuration) =
    TimedPull.go[IO, O, Chunk[O]] { tp =>
      def emitNonEmpty(c: Chunk.Queue[O]): Pull[IO, Chunk[O], Unit] =
        if (c.size > 0) Pull.output1(c.toChunk)
        else Pull.done

      def resize(c: Chunk[O], s: Pull[IO, Chunk[O], Unit]): (Pull[IO, Chunk[O], Unit], Chunk[O]) =
        if (c.size < n) s -> c
        else {
          val (unit, rest) = c.splitAt(n)
          resize(rest, s >> Pull.output1(unit))
        }

      def go(acc: Chunk.Queue[O], tp: TimedPull[IO, O]): Pull[IO, Chunk[O], Unit] =
        tp.uncons.flatMap {
          case None => emitNonEmpty(acc)
          case Some((e, next)) =>
            e match {
              case Left(_) =>
                emitNonEmpty(acc) >> tp.startTimer(t) >> go(Chunk.Queue.empty, next)
              case Right(c) =>
                val newAcc = acc :+ c
                if (newAcc.size < n)
                  go(newAcc, next)
                else {
                  val (toEmit, rest) = resize(newAcc.toChunk, Pull.done)
                  toEmit >> tp.startTimer(t) >> go(Chunk.Queue(rest), next)
                }
            }
        }

      tp.startTimer(t) >> go(Chunk.Queue.empty, tp)
    }.apply(s)

  import cats.effect.unsafe.implicits.global

  def ex = {
    def s(as: Int*): Stream[IO, Int] = Stream(as:_*)
    def t(d: FiniteDuration) = Stream.sleep_[IO](d)

    s(1,2,3,4,5) ++ s(6,7,8,9,10) ++ t(300.millis) ++ s(11,12,13) ++ t(200.millis) ++ s(14) ++ s(5,5,5) ++ t(600.millis) ++ s(15, 16, 17) ++ s(18, 19, 20, 21, 22) ++ t(1.second)
  }

  def t = ex.groupWithin(5, 1.second).debug().compile.toVector.unsafeRunSync()
  def tt = groupWithin(ex, 5, 1.second).debug().compile.toVector.unsafeRunSync()



  // components:
  //  a queue of chunks and timeouts
  //  a mechanism for resettable timeouts
  //  a way to avoid reacting to stale timeouts

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

  // def groupWithin[F[x] >: F[x]](
  //     n: Int,
  //     d: FiniteDuration
  // )(implicit F: Temporal[F2]): Stream[F2, Chunk[O]] =
  //   Stream
  //     .eval {
  //       Queue
  //         .synchronousNoneTerminated[F2, Either[Token, Chunk[O]]]
  //         .product(F.ref(F.unit -> false))
  //     }
  //     .flatMap { case (q, currentTimeout) =>
  //       def startTimeout: Stream[F2, Token] =
  //         Stream.eval(Token[F2]).evalTap { token =>
  //           val timeout = F.sleep(d) >> q.enqueue1(token.asLeft.some)

  //           // We need to cancel outstanding timeouts to avoid leaks
  //           // on interruption, but using `Stream.bracket` or
  //           // derivatives causes a memory leak due to all the
  //           // finalisers accumulating. Therefore we dispose of them
  //           // manually, with a cooperative strategy between a single
  //           // stream finaliser, and F finalisers on each timeout.
  //           //
  //           // Note that to avoid races, the correctness of the
  //           // algorithm does not depend on timely cancellation of
  //           // previous timeouts, but uses a versioning scheme to
  //           // ensure stale timeouts are no-ops.
  //           timeout.start
  //             .bracket(_ => F.unit) { fiber =>
  //               // note the this is in a `release` action, and therefore uninterruptible
  //               currentTimeout.modify { case st @ (cancelInFlightTimeout, streamTerminated) =>
  //                 if (streamTerminated)
  //                   // the stream finaliser will cancel the in flight
  //                   // timeout, we need to cancel the timeout we have
  //                   // just started
  //                   st -> fiber.cancel
  //                 else
  //                   // The stream finaliser hasn't run, so we cancel
  //                   // the in flight timeout and store the finaliser for
  //                   // the timeout we have just started
  //                   (fiber.cancel, streamTerminated) -> cancelInFlightTimeout
  //               }.flatten
  //             }
  //         }

  //       def producer =
  //         this.chunks.map(_.asRight.some).through(q.enqueue).onFinalize(q.enqueue1(None))

  //       def emitNonEmpty(c: Chunk.Queue[O]): Stream[F2, Chunk[O]] =
  //         if (c.size > 0) Stream.emit(c.toChunk)
  //         else Stream.empty

  //       def resize(c: Chunk[O], s: Stream[F2, Chunk[O]]): (Stream[F2, Chunk[O]], Chunk[O]) =
  //         if (c.size < n) s -> c
  //         else {
  //           val (unit, rest) = c.splitAt(n)
  //           resize(rest, s ++ Stream.emit(unit))
  //         }

  //       def go(acc: Chunk.Queue[O], currentTimeout: Token): Stream[F2, Chunk[O]] =
  //         Stream.eval(q.dequeue1).flatMap {
  //           case None => emitNonEmpty(acc)
  //           case Some(e) =>
  //             e match {
  //               case Left(t) if t == currentTimeout =>
  //                 emitNonEmpty(acc) ++ startTimeout.flatMap { newTimeout =>
  //                   go(Chunk.Queue.empty, newTimeout)
  //                 }
  //               case Left(_) => go(acc, currentTimeout)
  //               case Right(c) if acc.size + c.size >= n =>
  //                 val newAcc = acc :+ c
  //                 // this is the same if in the resize function,
  //                 // short circuited to avoid needlessly converting newAcc.toChunk
  //                 if (newAcc.size < n)
  //                   Stream.empty ++ startTimeout.flatMap(newTimeout => go(newAcc, newTimeout))
  //                 else {
  //                   val (toEmit, rest) = resize(newAcc.toChunk, Stream.empty)
  //                   toEmit ++ startTimeout.flatMap { newTimeout =>
  //                     go(Chunk.Queue(rest), newTimeout)
  //                   }
  //                 }
  //               case Right(c) =>
  //                 go(acc :+ c, currentTimeout)
  //             }
  //         }

  //       startTimeout
  //         .flatMap(t => go(Chunk.Queue.empty, t).concurrently(producer))
  //         .onFinalize {
  //           currentTimeout
  //             .getAndSet(F.unit -> true)
  //             .flatMap { case (cancelInFlightTimeout, _) => cancelInFlightTimeout }
  //         }
  //     }

   // def go(acc: Chunk.Queue[O], currentTimeout: Token): Stream[F2, Chunk[O]] =
   //        Stream.eval(q.dequeue1).flatMap {
   //          case None => emitNonEmpty(acc)
   //          case Some(e) =>
   //            e match {
   //              case Left(t) if t == currentTimeout =>
   //                emitNonEmpty(acc) ++ startTimeout.flatMap { newTimeout =>
   //                  go(Chunk.Queue.empty, newTimeout)
   //                }
   //              case Left(_) => go(acc, currentTimeout)
   //              case Right(c) =>
   //                val newAcc = acc :+ c
   //                if (newAcc.size < n)
   //                  go(newAcc, currentTimeout)
   //                else {
   //                  val (toEmit, rest) = resize(newAcc.toChunk, Stream.empty)
   //                  toEmit ++ startTimeout.flatMap { newTimeout =>
   //                    go(Chunk.Queue(rest), newTimeout)
   //                  }
   //                }
   //            }
   //        }


}
