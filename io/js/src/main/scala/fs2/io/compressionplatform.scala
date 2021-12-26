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
import cats.effect.syntax.all._
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

      override def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] = in =>
        inflateAndTrailer(inflateParams, 0)(in).flatMap(_._1)

      private def inflateAndTrailer(
          inflateParams: InflateParams,
          trailerSize: Int
      ): Stream[F, Byte] => Stream[F, (Stream[F, Byte], Deferred[F, Chunk[Byte]])] = in => {
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
          w <- Stream.resource(weird(bytesWritten, trailerSize)(in))
          (main, trailer) = w
          inflated = out
            .concurrently(
              main.through(writeWritable[F](inflate.asInstanceOf[Writable].pure))
            )
            .onFinalize(
              F.async_[Unit] { cb =>
                inflate.asInstanceOf[zlibMod.Zlib].close(() => cb(Right(())))
              }
            )
        } yield (inflated, trailer)
      }

      private def weird(
          bytesWritten: Deferred[F, Int], // completed when the downstream (inflate) is done
          trailerSize: Int
      ): Stream[F, Byte] => Resource[F, (Stream[F, Byte], Deferred[F, Chunk[Byte]])] = in =>
        (
          Resource.eval(F.deferred[Chunk[Byte]]),
          Resource.eval(F.ref(Chunk.empty[Byte])),
          Resource.eval(F.ref(0)),
          Resource.eval(F.ref(false))
        ).mapN { (trailerChunkDeferred, lastChunkRef, bytesPipedRef, streamDone) =>
          F.background {
            // total bytes consumed by the inflate (might be less than bytesPiped below)
            bytesWritten.get.flatMap { bytesWritten =>
              // total bytes piped from the upstream into inflate (some might not be consumed)
              bytesPipedRef.get.flatMap { bytesPiped =>
                // last chunk piped from the upstream into inflate (might have been consumed partially)
                lastChunkRef.get.flatMap { lastChunk =>
                  // whether the upstream has completed (uncons received None)
                  streamDone.get.flatMap { streamDone =>
                    if (streamDone || bytesPiped - bytesWritten >= trailerSize) {
                      // if we have enough bytes or if the upstream has completed thus no more bytes to expect
                      trailerChunkDeferred
                        .complete(
                          lastChunk.takeRight(bytesPiped - bytesWritten).take(trailerSize)
                        )
                        .void
                    } else {
                      // not enough bytes yet and expecting more bytes (upstream not completed yet)
                      F.unit
                    }
                  }
                }
              }
            }
          }.as {

            def go: Stream[F, Byte] => Pull[F, Byte, Unit] =
              _.pull.uncons.flatMap {
                case None =>
                  Pull.eval {
                    // in case bytesWritten will gets completed *after* this [race]
                    streamDone.set(true) >>
                      trailerChunkDeferred.tryGet.flatMap {
                        case Some(_) =>
                          // bytesWritten completion might have completed trailerChunkDeferred already
                          F.unit
                        case None =>
                          bytesWritten.tryGet.flatMap {
                            case None =>
                              // bytesWritten has not been completed yet
                              F.unit
                            case Some(bytesWritten) =>
                              lastChunkRef.get.flatMap { lastChunk =>
                                bytesPipedRef.get.flatMap { bytesPiped =>
                                  // complete with whatever we have, no more bytes to come
                                  trailerChunkDeferred
                                    .complete(
                                      lastChunk
                                        .takeRight(bytesPiped - bytesWritten)
                                        .take(trailerSize)
                                    )
                                    .void
                                }
                              }
                          }
                      }
                  }
                case Some((chunk, rest)) =>
                  Pull.eval(trailerChunkDeferred.tryGet).flatMap {
                    case Some(_) =>
                      // we're done already
                      Pull.done
                    case None =>
                      Pull.eval(bytesWritten.tryGet).flatMap {
                        case None =>
                          // downstream is still consuming, keep the current chunk and
                          // increase bytesPiped
                          Pull.eval {
                            lastChunkRef.set(chunk) >> bytesPipedRef.update(_ + chunk.size)
                          } >> Pull.output(chunk) >> go(rest)
                        case Some(bytesWritten) =>
                          Pull.eval(bytesPipedRef.get).flatMap { bytesPiped =>
                            Pull.eval(lastChunkRef.get).flatMap { last =>
                              if (bytesPiped - bytesWritten >= trailerSize) {
                                // have enough bytes to complete
                                Pull
                                  .eval(
                                    trailerChunkDeferred.complete(
                                      (last ++ chunk)
                                        .takeRight(bytesPiped - bytesWritten)
                                        .take(trailerSize)
                                    ) >> lastChunkRef.set(Chunk.empty)
                                  )
                                  .void
                              } else {
                                // downstream is done consuming, but we need more bytes
                                Pull.eval {
                                  lastChunkRef.set(
                                    // keep what we have (discarding what has been consumed by the downstream, just to save mem)
                                    // plus the new chunk
                                    last.takeRight(bytesPiped - bytesWritten) ++ chunk
                                  ) >>
                                    // keep track of bytesPiped
                                    bytesPipedRef.update(_ + chunk.size)
                                } >> go(rest) // keep pulling
                              }
                            }
                          }
                      }
                  }
              }

            (
              go(in).stream,
              trailerChunkDeferred
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
        gzip.gunzip(inflateAndTrailer(inflateParams, gzip.gzipTrailerBytes), inflateParams)

    }
}
