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

import cats.effect.kernel.{Async, Outcome, Resource, Sync}
import cats.effect.kernel.implicits._
import cats.effect.kernel.Deferred
import cats.syntax.all._
import fs2.io.internal.PipedStreamBuffer

import java.io.{InputStream, OutputStream}

private[fs2] trait ioplatform {

  type IOException = java.io.IOException

  /** Pipe that converts a stream of bytes to a stream that will emit a single `java.io.InputStream`,
    * that is closed whenever the resulting stream terminates.
    *
    * If the `close` of resulting input stream is invoked manually, then this will await until the
    * original stream completely terminates.
    *
    * Because all `InputStream` methods block (including `close`), the resulting `InputStream`
    * should be consumed on a different thread pool than the one that is backing the effect.
    *
    * Note that the implementation is not thread safe -- only one thread is allowed at any time
    * to operate on the resulting `java.io.InputStream`.
    */
  def toInputStream[F[_]: Async]: Pipe[F, Byte, InputStream] =
    source => Stream.resource(toInputStreamResource(source))

  /** Like [[toInputStream]] but returns a `Resource` rather than a single element stream.
    */
  def toInputStreamResource[F[_]: Async](
      source: Stream[F, Byte]
  ): Resource[F, InputStream] =
    JavaInputOutputStream.toInputStream(source)

  /** Take a function that emits to an [[java.io.OutputStream OutputStream]] effectfully,
    * and return a stream which, when run, will perform that function and emit
    * the bytes recorded in the OutputStream as an fs2.Stream
    *
    * The stream produced by this will terminate if:
    *   - `f` returns
    *   - `f` calls `OutputStream#close`
    *
    * If none of those happens, the stream will run forever.
    */
  def readOutputStream[F[_]: Async](
      chunkSize: Int
  )(
      f: OutputStream => F[Unit]
  ): Stream[F, Byte] = {
    val mkOutput: Resource[F, (OutputStream, InputStream)] =
      Resource.make(Sync[F].delay {
        val buf = new PipedStreamBuffer(chunkSize)
        (buf.outputStream, buf.inputStream)
      })(ois =>
        Sync[F].blocking {
          // Piped(I/O)Stream implementations cant't throw on close, no need to nest the handling here.
          ois._2.close()
          ois._1.close()
        }
      )

    Stream.resource(mkOutput).flatMap { case (os, is) =>
      Stream.eval(Deferred[F, Option[Throwable]]).flatMap { err =>
        // We need to close the output stream regardless of how `f` finishes
        // to ensure an outstanding blocking read on the input stream completes.
        // In such a case, there's a race between completion of the read
        // stream and finalization of the write stream, so we capture the error
        // that occurs when writing and rethrow it.
        val write = f(os).guaranteeCase((outcome: Outcome[F, Throwable, Unit]) =>
          Sync[F].blocking(os.close()) *> err
            .complete(outcome match {
              case Outcome.Errored(t) => Some(t)
              case _                  => None
            })
            .void
        )
        val read = readInputStream(is.pure[F], chunkSize, closeAfterUse = false)
        read.concurrently(Stream.eval(write)) ++ Stream.eval(err.get).flatMap {
          case None    => Stream.empty
          case Some(t) => Stream.raiseError[F](t)
        }
      }
    }
  }

}
