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
package io

import java.io.{IOException, InputStream}

import cats.syntax.all._
import cats.effect.kernel.{Async, Outcome, Resource}
import cats.effect.kernel.implicits._
import cats.effect.std.Dispatcher

import fs2.concurrent.{Queue, SignallingRef}

private[io] object JavaInputOutputStream {

  /** state of the upstream, we only indicate whether upstream is done and if it failed * */
  private final case class UpStreamState(done: Boolean, err: Option[Throwable])
  private sealed trait DownStreamState { self =>
    def isDone: Boolean =
      self match {
        case Done(_) => true
        case _       => false
      }
  }
  private final case class Done(rslt: Option[Throwable]) extends DownStreamState
  private final case class Ready(rem: Option[Chunk.ArraySlice[Byte]]) extends DownStreamState

  def toInputStream[F[_]](
      source: Stream[F, Byte]
  )(implicit F: Async[F]): Resource[F, InputStream] = {
    def markUpstreamDone(
        queue: Queue[F, Either[Option[Throwable], Chunk.ArraySlice[Byte]]],
        upState: SignallingRef[F, UpStreamState],
        result: Option[Throwable]
    ): F[Unit] =
      upState.set(UpStreamState(done = true, err = result)) >> queue.enqueue1(Left(result))

    /* Takes source and runs it through queue, interrupting when dnState signals stream is done.
     * Note when the exception in stream is encountered the exception is emitted on the left to the queue
     * and that would be the last message enqueued.
     *
     * Emits only once, but runs in background until either source is exhausted or `interruptWhenTrue` yields to true
     */
    def processInput(
        source: Stream[F, Byte],
        queue: Queue[F, Either[Option[Throwable], Chunk.ArraySlice[Byte]]],
        upState: SignallingRef[F, UpStreamState],
        dnState: SignallingRef[F, DownStreamState]
    ): F[Unit] =
      source.chunks
        .evalMap(ch => queue.enqueue1(Right(ch.toArraySlice)))
        .interruptWhen(dnState.discrete.map(_.isDone).filter(identity))
        .compile
        .drain
        .guaranteeCase { (outcome: Outcome[F, Throwable, Unit]) =>
          outcome match {
            case Outcome.Succeeded(_) => markUpstreamDone(queue, upState, None)
            case Outcome.Errored(t)   => markUpstreamDone(queue, upState, Some(t))
            case Outcome.Canceled()   => markUpstreamDone(queue, upState, None)
          }
        }
        .start
        .void

    def readOnce(
        dest: Array[Byte],
        off: Int,
        len: Int,
        queue: Queue[F, Either[Option[Throwable], Chunk.ArraySlice[Byte]]],
        dnState: SignallingRef[F, DownStreamState]
    ): F[Int] = {
      // in case current state has any data available from previous read
      // this will cause the data to be acquired, state modified and chunk returned
      // won't modify state if the data cannot be acquired
      def tryGetChunk(s: DownStreamState): (DownStreamState, Option[Chunk.ArraySlice[Byte]]) =
        s match {
          case Done(None)    => s -> None
          case Done(Some(_)) => s -> None
          case Ready(None)   => s -> None
          case Ready(Some(bytes)) =>
            val cloned = Chunk.ArraySlice(bytes.toArray)
            if (bytes.size <= len) Ready(None) -> Some(cloned)
            else {
              val (out, rem) = cloned.splitAt(len)
              Ready(Some(rem.toArraySlice)) -> Some(out.toArraySlice)
            }
        }

      def setDone(rsn: Option[Throwable])(s0: DownStreamState): DownStreamState =
        s0 match {
          case s @ Done(_) => s
          case _           => Done(rsn)
        }

      dnState.modify { s =>
        val (n, out) = tryGetChunk(s)

        val result = out match {
          case Some(bytes) =>
            F.delay {
              Array.copy(bytes.values, bytes.offset, dest, off, bytes.size)
              bytes.size
            }
          case None =>
            n match {
              case Done(None) => -1.pure[F]
              case Done(Some(err)) =>
                F.raiseError[Int](new IOException("Stream is in failed state", err))
              case _ =>
                // Ready is guaranteed at this time to be empty
                queue.dequeue1.flatMap {
                  case Left(None) =>
                    dnState
                      .update(setDone(None))
                      .as(-1) // update we are done, next read won't succeed
                  case Left(Some(err)) => // update we are failed, next read won't succeed
                    dnState.update(setDone(err.some)) >> F.raiseError[Int](
                      new IOException("UpStream failed", err)
                    )
                  case Right(bytes) =>
                    val (copy, maybeKeep) =
                      if (bytes.size <= len) bytes -> None
                      else {
                        val (out, rem) = bytes.splitAt(len)
                        out.toArraySlice -> rem.toArraySlice.some
                      }
                    F.delay {
                      Array.copy(copy.values, copy.offset, dest, off, copy.size)
                    } >> (maybeKeep match {
                      case Some(rem) if rem.size > 0 =>
                        dnState.set(Ready(rem.some)).as(copy.size)
                      case _ => copy.size.pure[F]
                    })
                }
            }
        }

        n -> result
      }.flatten
    }

    def closeIs(
        upState: SignallingRef[F, UpStreamState],
        dnState: SignallingRef[F, DownStreamState]
    ): F[Unit] =
      dnState.update {
        case s @ Done(_) => s
        case _           => Done(None)
      } >>
        upState.discrete
          .collectFirst { case UpStreamState(true, maybeErr) =>
            maybeErr // await upStreamDome to yield as true
          }
          .compile
          .last
          .flatMap {
            _.flatten match {
              case None      => F.unit
              case Some(err) => F.raiseError[Unit](err)
            }
          }

    /*
     * Implementation note:
     *
     * We run this through 3 synchronous primitives
     *
     * - Synchronous Queue -  used to signal next available chunk, or when the upstream is done/failed
     * - UpStream signal -    used to monitor state of upstream, primarily to indicate to `close`
     *                        that upstream has finished and is safe time to terminate
     * - DownStream signal -  keeps any remainders from last `read` and signals
     *                        that downstream has been terminated that in turn kills upstream
     */
    Dispatcher[F].flatMap { dispatcher =>
      Resource
        .liftF(
          (
            Queue.synchronous[F, Either[Option[Throwable], Chunk.ArraySlice[Byte]]],
            SignallingRef.of[F, UpStreamState](UpStreamState(done = false, err = None)),
            SignallingRef.of[F, DownStreamState](Ready(None))
          ).tupled
        )
        .flatMap { case (queue, upState, dnState) =>
          val mkInputStream = processInput(source, queue, upState, dnState)
            .as(
              new InputStream {
                override def close(): Unit =
                  dispatcher.unsafeRunAndForget(closeIs(upState, dnState))

                override def read(b: Array[Byte], off: Int, len: Int): Int =
                  dispatcher.unsafeRunSync(readOnce(b, off, len, queue, dnState))

                def read(): Int = {
                  def go(acc: Array[Byte]): F[Int] =
                    readOnce(acc, 0, 1, queue, dnState).flatMap { read =>
                      if (read < 0) F.pure(-1)
                      else if (read == 0) go(acc)
                      else F.pure(acc(0) & 0xff)
                    }

                  dispatcher.unsafeRunSync(go(new Array[Byte](1)))
                }
              }
            )

          Resource.make(mkInputStream)(_ => closeIs(upState, dnState))
        }
    }
  }
}
