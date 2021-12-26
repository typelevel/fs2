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

import cats.effect.{Concurrent, Deferred, Resource}
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.compression._
import fs2.internal.jsdeps.node.anon.BytesWritten
import fs2.internal.jsdeps.node.zlibMod
import fs2.internal.jsdeps.node.zlibMod.Zlib

import scala.concurrent.duration.FiniteDuration

private[fs2] trait compressionplatform {

  implicit def fs2ioCompressionForAsync[F[_]: Concurrent](implicit F: Async[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      private val gzip = new Gzip[F]

      override def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(deflateParams.bufferSizeOrMinimum.toDouble)
          .setLevel(deflateParams.level.juzDeflaterLevel.toDouble)
          .setStrategy(deflateParams.strategy.juzDeflaterStrategy.toDouble)
          .setFlush(deflateParams.flushMode.juzDeflaterFlushMode.toDouble)

        Stream
          .resource(suspendReadableAndRead() {
            (deflateParams.header match {
              case ZLibParams.Header.GZIP => zlibMod.createDeflateRaw(options)
              case ZLibParams.Header.ZLIB => zlibMod.createDeflate(options)
            }).asInstanceOf[Duplex]
          })
          .flatMap { case (deflate, out) =>
            out
              .concurrently(in.through(writeWritable[F](deflate.pure.widen)))
              .onFinalize(
                F.async_[Unit](cb => deflate.asInstanceOf[zlibMod.Zlib].close(() => cb(Right(()))))
              )
          }
      }

      override def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(inflateParams.bufferSizeOrMinimum.toDouble)

        inflateAndRest(inflateParams)(in).flatMap { case (inflated, rest) =>
          inflated.onFinalizeCase {
            case Resource.ExitCase.Succeeded => rest.compile.drain
            case _                           => F.unit
          }
        }
      }

      private def inflateAndRest(
          inflateParams: InflateParams
      ): Stream[F, Byte] => Stream[F, (Stream[F, Byte], Stream[F, Byte])] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(inflateParams.bufferSizeOrMinimum.toDouble)

        for {
          suspended <- Stream
            .resource(suspendReadableAndReadZlib() {
              (inflateParams.header match {
                case ZLibParams.Header.GZIP => zlibMod.createInflateRaw(options)
                case ZLibParams.Header.ZLIB => zlibMod.createInflate(options)
              })
            })
          (inflate, out, bytesWritten) = suspended
          w <- Stream.resource(weird(bytesWritten, 10 * 1024)(in))
          (main, rest) = w
          inflated = out
            .concurrently(
              main.through(writeWritable[F](inflate.asInstanceOf[Writable].pure))
            )
            .onFinalize(
              F.async_[Unit] { cb =>
                inflate.asInstanceOf[zlibMod.Zlib].close(() => cb(Right(())))
              }
            )
        } yield (inflated, rest)
      }

      private def weird(
          bytesWrittenDeferred: Deferred[F, Int],
          bufferLimitSoft: Int
      ): Stream[F, Byte] => Resource[F, (Stream[F, Byte], Stream[F, Byte])] = in =>
        (
          Resource.eval(Queue.unbounded[F, Option[Chunk[Byte]]]),
          Resource.eval(F.ref(Chunk.empty[Byte])),
          Resource.eval(F.ref(0)),
          Resource.eval(F.ref(false)),
          Resource.eval(F.ref(false)),
          Resource.eval(F.ref(false))
        ).mapN { (tailQueue, keptChunk, bytesThrough, writingToTail, offeredKeptChunk, done) =>
          F.background(
            bytesWrittenDeferred.get.flatMap { bytesWritten =>
              writingToTail.set(true) >>
                bytesThrough.get.flatMap { bytesThrough =>
                  keptChunk.get.flatMap { keep =>
                    done.get.flatMap { done =>
                      val toRest = keep.takeRight(bytesThrough - bytesWritten)
                      tailQueue.offer(Some(toRest)) >> F.whenA(done)(tailQueue.offer(none))
                    }
                  } >> keptChunk.set(Chunk.empty) >> offeredKeptChunk.set(true)
                }
            }
          ).as {
            def go: Stream[F, Byte] => Pull[F, Byte, Unit] =
              _.pull.uncons.flatMap {
                case None =>
                  Pull.eval {
                    offeredKeptChunk.get.flatMap {
                      case true  => tailQueue.offer(none)
                      case false => done.set(true)
                    }
                  }
                case Some((chunk, rest)) =>
                  Pull.eval(keptChunk.get).flatMap { keep =>
                    Pull.eval(writingToTail.get).flatMap {
                      case false =>
                        val newKeep =
                          if (keep.size + chunk.size > bufferLimitSoft / 2) {
                            keep.take(keep.size / 2) ++ chunk
                          } else {
                            keep ++ chunk
                          }
                        Pull.eval {
                          keptChunk.set(newKeep) >>
                            bytesThrough.update(_ + chunk.size)
                        } >> Pull.output(chunk) >> go(rest)
                      case true =>
                        Pull.eval(tailQueue.offer(Some(chunk))) >> go(rest)
                    }
                  }
              }

            (
              go(in).stream,
              Stream.fromQueueNoneTerminatedChunk(tailQueue)
            )
          }
        }.flatten

      def gzip2(
          fileName: Option[String],
          modificationTime: Option[FiniteDuration],
          comment: Option[String],
          deflateParams: DeflateParams
      ): Pipe[F, Byte, Byte] =
        gzip.gzip(fileName, modificationTime, comment, deflate(deflateParams), deflateParams)

      def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
        gzip.gunzip(inflateAndRest(inflateParams), inflateParams)

    }
}
