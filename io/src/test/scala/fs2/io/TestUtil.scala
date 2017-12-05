package fs2.io

import java.lang.Thread.UncaughtExceptionHandler
import java.net.{InetSocketAddress, SocketAddress}
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import fs2._
import fs2.io.tcp.Socket
import fs2.util.Async
import fs2.util.syntax._


import scala.concurrent.duration.FiniteDuration


object TestUtil {

  def namedACG(name:String):AsynchronousChannelGroup = {
    val idx = new AtomicInteger(0)
    AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
      8
      , new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"fs2-AG-$name-${idx.incrementAndGet() }")
          t.setDaemon(true)
          t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
            def uncaughtException(t: Thread, e: Throwable): Unit = {
              println("-------------- UNHANDLED EXCEPTION ------------")
              e.printStackTrace()
            }
          })
          t
        }
      }
    )
  }

  def localAddress(port: Int) = new InetSocketAddress("localhost", port)



  /**
    * Creates a local in-memory tcp socket, that when evaluated will start to consume `received` Stream
    * of source data and produces the local socket (to be used instead normal TCP socket) and outptu stream
    * that is constructed with data that vere `sent` over this local socket
    *
    */
  def localTcpSocket[F[_]](received: Stream[F, Byte])(implicit S: Strategy, F: Async[F]): Stream[F, (Socket[F], Stream[F, Byte])] = {

    // internal state for the received chunks
    case class Received(buff: Chunk[Byte], closed: Boolean, waiting: Vector[F[Unit]])

    Stream.eval(async.unboundedQueue[F, Option[Chunk[Byte]]]) flatMap { sendQueue =>
    Stream.eval(async.signalOf[F, Received](Received(Chunk.empty, false, Vector.empty))) flatMap { receivedSignal =>

        // offer chunk and run all watiting reads
        def offer(ch: NonEmptyChunk[Byte]): F[Unit] = {
          receivedSignal.modify { r => r.copy(buff = Chunk.concatBytes(Seq(r.buff, ch)), waiting = Vector.empty) } flatMap { c =>
            if (c.previous.waiting.isEmpty) F.pure(())
            else F.start(F.parallelSequence(c.previous.waiting)) as (())
          }
        }

        // close and run all watiting reads
        def closedInput: F[Unit] = {
          receivedSignal.modify { r => r.copy(closed = false, waiting = Vector.empty) } flatMap { c =>
            if (c.previous.waiting.isEmpty) F.pure(())
            else F.start(F.parallelSequence(c.previous.waiting)) as (())

          }
        }

        // read until bytes is satisfied.
        def take(bytes: Int, requireAll: Boolean): F[Option[Chunk[Byte]]] = {
          Async.ref[F, Unit].flatMap { signal =>
            receivedSignal.modify2 { r =>
              if (r.closed && r.buff.isEmpty) (r, Chunk.empty)
              else {
                val took = r.buff.take(bytes)
                val waiting = if ((requireAll && took.size < bytes) || took.isEmpty) r.waiting :+ signal.setPure(()) else r.waiting
                (r.copy(buff = r.buff.drop(bytes), waiting = waiting), took)
              }
            } flatMap { case (c, took) =>
              if (requireAll) {
                val remains = bytes - took.size
                if (remains == 0 || c.now.closed) F.pure(Some(took).filter(_.nonEmpty))
                else {
                  signal.get >> take(remains, requireAll) map { read =>
                    val all = Chunk.concatBytes(took +: read.toSeq)
                    Some(all).filter(_.nonEmpty)
                  }
                }
              } else {
                if (took.isEmpty && c.now.closed) F.pure(None)
                else if (took.nonEmpty) F.pure(Some(took))
                else signal.get >> take(bytes, requireAll)
              }
            }
          }
        }

        val enqueue = (received.chunks.evalMap { offer } onFinalize  closedInput) interruptWhen receivedSignal.map(_.closed)

        val socket =
          new Socket[F] {

            def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = take(maxBytes, requireAll = false)
            def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] = Stream.repeatEval(read(maxBytes, timeout)).unNoneTerminate.flatMap(Stream.chunk)
            def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =  take(numBytes, requireAll = true)
            def endOfInput: F[Unit] = receivedSignal.modify { _.copy(closed = true) } as (())
            def endOfOutput: F[Unit] = sendQueue.enqueue1(None)
            def close: F[Unit] = endOfOutput >> endOfInput
            def remoteAddress: F[SocketAddress] = F.fail(new Throwable("Not supported"))
            def localAddress: F[SocketAddress] = F.fail(new Throwable("Not supported"))
            def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] = sendQueue.enqueue1(Some(bytes))
            def writes(timeout: Option[FiniteDuration]): Sink[F, Byte] = _.chunks.evalMap(ch => write(ch, None))

          }

        Stream.emit((socket, sendQueue.dequeue.unNoneTerminate flatMap Stream.chunk)).covary[F] mergeHaltR enqueue.drain
      }}
  }

}

