package fs2
package io
package tls

import java.nio.{Buffer, ByteBuffer}

import cats.Applicative
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

/**
  * Wraps a pair of `ByteBuffer`s for use with an `SSLEngine` -- an input buffer and an output buffer.
  *
  * One or more chunks of bytes are added to the input buffer via the [[input]] method. Once input
  * data has been added, calling [[perform]] allows read access to the input and write access to the
  * output. After [[perform]], produced output can be read via the [[output]] method.
  */
private[tls] trait InputOutputBuffer[F[_]] {

  /** Adds the specified chunk to the input buffer. */
  def input(data: Chunk[Byte]): F[Unit]

  /** Removes all available data from the output buffer. */
  def output: F[Chunk[Byte]]

  /**
    * Performs an operation that may read from the input buffer and write to the output buffer.
    * When `f` is called, the input buffer has been flipped. After `f` completes, the input buffer
    * is compacted.
    */
  def perform[A](f: (ByteBuffer, ByteBuffer) => A): F[A]

  /** Expands the size of the output buffer. */
  def expandOutput: F[Unit]

  /** Returns the number of unread bytes that are currently in the input buffer. */
  def inputRemains: F[Int]
}

private[tls] object InputOutputBuffer {
  def apply[F[_]: Sync](inputSize: Int, outputSize: Int): F[InputOutputBuffer[F]] =
    for {
      inBuff <- Ref.of[F, ByteBuffer](ByteBuffer.allocate(inputSize))
      outBuff <- Ref.of[F, ByteBuffer](ByteBuffer.allocate(outputSize))
    } yield new InputOutputBuffer[F] {

      def input(data: Chunk[Byte]): F[Unit] =
        inBuff.get
          .flatMap { in =>
            if (in.remaining() >= data.size) Applicative[F].pure(in)
            else {
              val expanded = expandBuffer(in, capacity => (capacity + data.size).max(capacity * 2))
              inBuff.set(expanded).as(expanded)
            }
          }
          .flatMap { in =>
            Sync[F].delay {
              val bs = data.toBytes
              in.put(bs.values, bs.offset, bs.size)
            }
          }

      def perform[A](
          f: (ByteBuffer, ByteBuffer) => A
      ): F[A] =
        inBuff.get.flatMap { in =>
          outBuff.get.flatMap { out =>
            Sync[F].delay {
              (in: Buffer).flip()
              val result = f(in, out)
              in.compact()
              result
            }
          }
        }

      def output: F[Chunk[Byte]] =
        outBuff.get.flatMap { out =>
          if (out.position() == 0) Applicative[F].pure(Chunk.empty)
          else
            Sync[F].delay {
              (out: Buffer).flip()
              val cap = out.limit()
              val dest = new Array[Byte](cap)
              out.get(dest)
              (out: Buffer).clear()
              Chunk.bytes(dest)
            }
        }

      private def expandBuffer(buffer: ByteBuffer, resizeTo: Int => Int): ByteBuffer = {
        val copy = new Array[Byte](buffer.position())
        val next = ByteBuffer.allocate(resizeTo(buffer.capacity()))
        (buffer: Buffer).flip()
        buffer.get(copy)
        next.put(copy)
      }

      def expandOutput: F[Unit] = outBuff.update(old => expandBuffer(old, _ * 2))

      def inputRemains = inBuff.get.map(_.position())
    }
}
