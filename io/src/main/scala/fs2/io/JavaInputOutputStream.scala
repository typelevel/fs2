package fs2
package io

import java.io.{IOException, InputStream}

import cats.implicits._
import cats.effect.{ConcurrentEffect, ExitCase, Resource}
import cats.effect.implicits._

import fs2.Chunk.Bytes
import fs2.concurrent.{Queue, SignallingRef}

private[io] object JavaInputOutputStream {

  /** state of the upstream, we only indicate whether upstream is done and if it failed **/
  private final case class UpStreamState(done: Boolean, err: Option[Throwable])
  private sealed trait DownStreamState { self =>
    def isDone: Boolean = self match {
      case Done(_) => true
      case _       => false
    }
  }
  private final case class Done(rslt: Option[Throwable]) extends DownStreamState
  private final case class Ready(rem: Option[Bytes]) extends DownStreamState

  def toInputStream[F[_]](source: Stream[F, Byte])(
      implicit F: ConcurrentEffect[F]): Resource[F, InputStream] = {

    def markUpstreamDone(queue: Queue[F, Either[Option[Throwable], Bytes]],
                         upState: SignallingRef[F, UpStreamState],
                         result: Option[Throwable]): F[Unit] =
      upState.set(UpStreamState(done = true, err = result)) >> queue.enqueue1(Left(result))

    /**
      * Takes source and runs it through queue, interrupting when dnState signals stream is done.
      * Note when the exception in stream is encountered the exception is emitted on the left to the queue
      * and that would be the last message enqueued.
      *
      * Emits only once, but runs in background until either source is exhausted or `interruptWhenTrue` yields to true
      */
    def processInput(
        source: Stream[F, Byte],
        queue: Queue[F, Either[Option[Throwable], Bytes]],
        upState: SignallingRef[F, UpStreamState],
        dnState: SignallingRef[F, DownStreamState]
    ): F[Unit] =
      source.chunks
        .evalMap(ch => queue.enqueue1(Right(ch.toBytes)))
        .interruptWhen(dnState.discrete.map(_.isDone).filter(identity))
        .compile
        .drain
        .guaranteeCase {
          case ExitCase.Completed => markUpstreamDone(queue, upState, None)
          case ExitCase.Error(t)  => markUpstreamDone(queue, upState, Some(t))
          case ExitCase.Canceled  => markUpstreamDone(queue, upState, None)
        }
        .start
        .void

    def readOnce(
        dest: Array[Byte],
        off: Int,
        len: Int,
        queue: Queue[F, Either[Option[Throwable], Bytes]],
        dnState: SignallingRef[F, DownStreamState]
    ): F[Int] = {
      // in case current state has any data available from previous read
      // this will cause the data to be acquired, state modified and chunk returned
      // won't modify state if the data cannot be acquired
      def tryGetChunk(s: DownStreamState): (DownStreamState, Option[Bytes]) =
        s match {
          case Done(None)      => s -> None
          case Done(Some(err)) => s -> None
          case Ready(None)     => s -> None
          case Ready(Some(bytes)) =>
            val cloned = Chunk.Bytes(bytes.toArray)
            if (bytes.size <= len) Ready(None) -> Some(cloned)
            else {
              val (out, rem) = cloned.splitAt(len)
              Ready(Some(rem.toBytes)) -> Some(out.toBytes)
            }
        }

      def setDone(rsn: Option[Throwable])(s0: DownStreamState): DownStreamState = s0 match {
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
              case Done(None) => (-1).pure[F]
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
                      new IOException("UpStream failed", err))
                  case Right(bytes) =>
                    val (copy, maybeKeep) =
                      if (bytes.size <= len) bytes -> None
                      else {
                        val (out, rem) = bytes.splitAt(len)
                        out.toBytes -> rem.toBytes.some
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
        case other       => Done(None)
      } >>
        upState.discrete
          .collectFirst {
            case UpStreamState(true, maybeErr) =>
              maybeErr // await upStreamDome to yield as true
          }
          .compile
          .last
          .flatMap {
            _.flatten match {
              case None      => F.pure(())
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
    Resource
      .liftF(
        (
          Queue.synchronous[F, Either[Option[Throwable], Bytes]],
          SignallingRef[F, UpStreamState](UpStreamState(done = false, err = None)),
          SignallingRef[F, DownStreamState](Ready(None))
        ).tupled)
      .flatMap {
        case (queue, upState, dnState) =>
          val mkInputStream = processInput(source, queue, upState, dnState)
            .as(
              new InputStream {
                override def close(): Unit =
                  closeIs(upState, dnState).toIO.unsafeRunSync

                override def read(b: Array[Byte], off: Int, len: Int): Int =
                  readOnce(b, off, len, queue, dnState).toIO.unsafeRunSync

                def read(): Int = {
                  def go(acc: Array[Byte]): F[Int] =
                    readOnce(acc, 0, 1, queue, dnState).flatMap { read =>
                      if (read < 0) F.pure(-1)
                      else if (read == 0) go(acc)
                      else F.pure(acc(0) & 0xFF)
                    }

                  go(new Array[Byte](1)).toIO.unsafeRunSync
                }
              }
            )

          Resource.make(mkInputStream)(_ => closeIs(upState, dnState))
      }
  }
}
