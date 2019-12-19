package fs2
package io
package tls

import java.nio.{Buffer, ByteBuffer}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLEngineResult

import cats.Applicative
import cats.effect.Sync
import cats.implicits._

/**
  * Buffer that wraps two Input/Output buffers together and guards
  * for input/output operations to be performed sequentially
  */
private[io] trait InputOutputBuffer[F[_]] {

  /**
    * Feeds the `data`. I/O Buffer must be awaiting input
    */
  def input(data: Chunk[Byte], force: Boolean = false): F[Unit]

  /**
    * Gets result from any operation. must bnot be awaiting input
    */
  def output: F[Chunk[Byte]]

  /**
    * Performs given operation. Buffer must not be awaiting input
    */
  def perform(f: (ByteBuffer, ByteBuffer) => SSLEngineResult): F[SSLEngineResult]

  /**
    * Expands output buffer operation while copying all data eventually present in the buffer already
    * @return
    */
  def expandOutput: F[Unit]

  /** return remaining data in input during consume **/
  def inputRemains: F[Int]
}

private[io] object InputOutputBuffer {
  def apply[F[_]: Sync](inputSize: Int, outputSize: Int): F[InputOutputBuffer[F]] = Sync[F].delay {
    val inBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(inputSize))
    val outBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(outputSize))
    val awaitInput = new AtomicBoolean(true)

    new InputOutputBuffer[F] {

      def input(data: Chunk[Byte], force: Boolean): F[Unit] = Sync[F].suspend {
        if (awaitInput.compareAndSet(true, false)) {
          val in = inBuff.get
          val expanded =
            if (in.remaining() >= data.size) Applicative[F].pure(in)
            else expandBuffer(in, capacity => (capacity + data.size).max(capacity * 2))

          expanded.flatMap { buff =>
            Sync[F].delay {
              inBuff.set(buff)
              val bs = data.toBytes
              buff.put(bs.values, bs.offset, bs.size)
              (buff: Buffer).flip()
              // outBuff.get().clear()
            }
          }
        } else {
          if (force) Sync[F].unit
          else
            Sync[F].raiseError(new RuntimeException("input bytes allowed only when awaiting input"))
        }
      }

      def perform(
          f: (ByteBuffer, ByteBuffer) => SSLEngineResult
      ): F[SSLEngineResult] = Sync[F].suspend {
        if (awaitInput.get)
          Sync[F].raiseError(new RuntimeException("Perform cannot be invoked when awaiting input"))
        else {
          awaitInput.set(false)
          Sync[F].delay(f(inBuff.get, outBuff.get))
        }
      }

      def output: F[Chunk[Byte]] = Sync[F].suspend {
        if (awaitInput.compareAndSet(false, true)) {
          val in = inBuff.get()
          in.compact()

          val out = outBuff.get()
          if (out.position() == 0) Applicative[F].pure(Chunk.empty)
          else {
            (out: Buffer).flip()
            val cap = out.limit()
            val dest = Array.ofDim[Byte](cap)
            out.get(dest)
            out.clear()
            Applicative[F].pure(Chunk.bytes(dest))
          }
        } else {
          Sync[F].raiseError(
            new RuntimeException("output bytes allowed only when not awaiting input")
          )
        }
      }

      private def expandBuffer(buffer: ByteBuffer, resizeTo: Int => Int): F[ByteBuffer] =
        Sync[F].suspend {
          val copy = Array.ofDim[Byte](buffer.position())
          val next = ByteBuffer.allocate(resizeTo(buffer.capacity()))
          (buffer: Buffer).flip()
          buffer.get(copy)
          Applicative[F].pure(next.put(copy))
        }

      def expandOutput: F[Unit] = expandBuffer(outBuff.get, _ * 2).map(outBuff.set(_))

      def inputRemains = Sync[F].delay { inBuff.get().remaining() }
    }
  }
}
