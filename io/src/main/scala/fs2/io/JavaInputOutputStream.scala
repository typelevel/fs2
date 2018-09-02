package fs2
package io

import scala.concurrent.{SyncVar, blocking}

import java.io.{IOException, InputStream}

import cats.effect.{ConcurrentEffect, ExitCase, IO}
import cats.effect.implicits._
import cats.implicits.{catsSyntaxEither => _, _}

import fs2.Chunk.Bytes
import fs2.concurrent.{Queue, SignallingRef}

private[io] object JavaInputOutputStream {

  def toInputStream[F[_]](implicit F: ConcurrentEffect[F]): Pipe[F, Byte, InputStream] = {

    /** See Implementation notes at the end of this code block **/
    /** state of the upstream, we only indicate whether upstream is done and if it failed **/
    final case class UpStreamState(done: Boolean, err: Option[Throwable])
    final case class Done(rslt: Option[Throwable]) extends DownStreamState
    final case class Ready(rem: Option[Bytes]) extends DownStreamState
    sealed trait DownStreamState { self =>
      def isDone: Boolean = self match {
        case Done(_) => true
        case _       => false
      }
    }

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
    ): Stream[F, Unit] =
      Stream
        .eval(F.start {
          def markUpstreamDone(result: Option[Throwable]): F[Unit] =
            F.flatMap(upState.set(UpStreamState(done = true, err = result))) { _ =>
              queue.enqueue1(Left(result))
            }

          F.guaranteeCase(
            source.chunks
              .evalMap(ch => queue.enqueue1(Right(ch.toBytes)))
              .interruptWhen(dnState.discrete.map(_.isDone).filter(identity))
              .compile
              .drain
          ) {
            case ExitCase.Completed => markUpstreamDone(None)
            case ExitCase.Error(t)  => markUpstreamDone(Some(t))
            case ExitCase.Canceled  => markUpstreamDone(None)
          }
        })
        .map(_ => ())

    /**
      * Closes the stream if not closed yet.
      * If the stream is closed, this will return once the upstream stream finishes its work and
      * releases any resources that upstream may hold.
      */
    def closeIs(
        upState: SignallingRef[F, UpStreamState],
        dnState: SignallingRef[F, DownStreamState]
    ): Unit = {
      val done = new SyncVar[Either[Throwable, Unit]]
      close(upState, dnState).start.flatMap(_.join).runAsync(r => IO(done.put(r))).unsafeRunSync
      blocking(done.get.fold(throw _, identity))
    }

    /**
      * Reads single chunk of bytes of size `len` into array b.
      *
      * This is implementation of InputStream#read.
      *
      * Inherently this method will block until data from the queue are available
      */
    def readIs(
        dest: Array[Byte],
        off: Int,
        len: Int,
        queue: Queue[F, Either[Option[Throwable], Bytes]],
        dnState: SignallingRef[F, DownStreamState]
    ): Int = {
      val sync = new SyncVar[Either[Throwable, Int]]
      readOnce(dest, off, len, queue, dnState).start
        .flatMap(_.join)
        .runAsync(r => IO(sync.put(r)))
        .unsafeRunSync
      blocking(sync.get.fold(throw _, identity))
    }

    /**
      * Reads single int value
      *
      * This is implementation of InputStream#read.
      *
      * Inherently this method will block until data from the queue are available
      *
      *
      */
    def readIs1(
        queue: Queue[F, Either[Option[Throwable], Bytes]],
        dnState: SignallingRef[F, DownStreamState]
    ): Int = {

      def go(acc: Array[Byte]): F[Int] =
        F.flatMap(readOnce(acc, 0, 1, queue, dnState)) { read =>
          if (read < 0) F.pure(-1)
          else if (read == 0) go(acc)
          else F.pure(acc(0) & 0xFF)
        }

      val sync = new SyncVar[Either[Throwable, Int]]
      go(new Array[Byte](1)).start.flatMap(_.join).runAsync(r => IO(sync.put(r))).unsafeRunSync
      blocking(sync.get.fold(throw _, identity))
    }

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
              Array.copy(bytes.values, 0, dest, off, bytes.size)
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
                    dnState.update(setDone(err.some)) *> F.raiseError[Int](
                      new IOException("UpStream failed", err))
                  case Right(bytes) =>
                    val (copy, maybeKeep) =
                      if (bytes.size <= len) bytes -> None
                      else {
                        val (out, rem) = bytes.splitAt(len)
                        out.toBytes -> rem.toBytes.some
                      }
                    F.delay {
                      Array.copy(copy.values, 0, dest, off, copy.size)
                    } *> (maybeKeep match {
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

    /**
      * Closes input stream and awaits completion of the upstream
      */
    def close(
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
     *
     */
    (source: Stream[F, Byte]) =>
      Stream
        .eval(Queue.synchronous[F, Either[Option[Throwable], Bytes]])
        .flatMap { queue =>
          Stream
            .eval(SignallingRef[F, UpStreamState](UpStreamState(done = false, err = None)))
            .flatMap { upState =>
              Stream
                .eval(SignallingRef[F, DownStreamState](Ready(None)))
                .flatMap { dnState =>
                  processInput(source, queue, upState, dnState)
                    .map { _ =>
                      new InputStream {
                        override def close(): Unit = closeIs(upState, dnState)
                        override def read(b: Array[Byte], off: Int, len: Int): Int =
                          readIs(b, off, len, queue, dnState)
                        def read(): Int = readIs1(queue, dnState)
                      }
                    }
                    .onFinalize(close(upState, dnState))
                }
            }
        }
  }
}
