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
package net
package tls

import java.nio.{Buffer, ByteBuffer}

import cats.Applicative
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all._

/** Wraps a pair of `ByteBuffer`s for use with an `SSLEngine` -- an input buffer and an output buffer.
  *
  * One or more chunks of bytes are added to the input buffer via the [[input]] method. Once input
  * data has been added, calling [[perform]] allows read access to the input and write access to the
  * output. After [[perform]], produced output can be read via the [[output]] method.
  */
private[tls] trait InputOutputBuffer[F[_]] {

  /** Adds the specified chunk to the input buffer. */
  def input(data: Chunk[Byte]): F[Unit]

  /** Removes available data from the output buffer. */
  def output(maxBytes: Int): F[Chunk[Byte]]

  /** Performs an operation that may read from the input buffer and write to the output buffer.
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
      inBuff <- Ref[F].of[ByteBuffer](ByteBuffer.allocate(inputSize))
      outBuff <- Ref[F].of[ByteBuffer](ByteBuffer.allocate(outputSize))
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
              val bs = data.toArraySlice
              in.put(bs.values, bs.offset, bs.size)
              ()
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

      def output(maxBytes: Int): F[Chunk[Byte]] =
        outBuff.get.flatMap { out =>
          if (out.position() == 0) Applicative[F].pure(Chunk.empty)
          else
            Sync[F].delay {
              (out: Buffer).flip()
              val cap = out.limit()
              val sz = cap.min(maxBytes)
              val dest = new Array[Byte](sz)
              out.get(dest)
              out.compact()
              Chunk.array(dest)
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
