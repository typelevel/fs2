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

import cats.effect._
import fs2.compression._
import fs2.io.compression._
import fs2.io.internal.facade
import org.scalacheck.effect.PropF.forAllF

import java.nio.charset.StandardCharsets

class NodeJSCompressionSuite extends CompressionSuite {

  def deflateStream(
      b: Array[Byte],
      level: Int,
      strategy: Int,
      nowrap: Boolean
  ): Array[Byte] = {
    val in = Chunk.array(b).toUint8Array
    val options = new facade.zlib.Options {}
    options.level = level
    options.strategy = strategy
    val out =
      if (nowrap)
        facade.zlib.deflateRawSync(in, options)
      else
        facade.zlib.deflateSync(in, options)
    Chunk.uint8Array(out).toArray
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val in = Chunk.array(b).toUint8Array
    val options = new facade.zlib.Options {}
    val out =
      if (nowrap)
        facade.zlib.inflateRawSync(in, options)
      else
        facade.zlib.inflateSync(in, options)
    Chunk.uint8Array(out).toArray
  }

  test("gzip |> gunzip ~= id") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip(
              fileName = None,
              modificationTime = None,
              comment = None,
              DeflateParams(
                bufferSize = 8192,
                header = ZLibParams.Header.GZIP,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gunzip(8192)
          )
          .flatMap { gunzipResult =>
            gunzipResult.content
          }
          .compile
          .toVector
          .assertEquals(s.getBytes.toVector)
    }
  }

  test("gzip |> gunzip ~= id (mutually prime chunk sizes, compression larger)") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip(
              fileName = None,
              modificationTime = None,
              comment = None,
              DeflateParams(
                bufferSize = 1031,
                header = ZLibParams.Header.GZIP,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gunzip(509)
          )
          .flatMap { gunzipResult =>
            gunzipResult.content
          }
          .compile
          .toVector
          .assertEquals(s.getBytes.toVector)
    }
  }

  test("gzip |> gunzip ~= id (mutually prime chunk sizes, decompression larger)") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip(
              fileName = None,
              modificationTime = None,
              comment = None,
              DeflateParams(
                bufferSize = 509,
                header = ZLibParams.Header.GZIP,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gunzip(1031)
          )
          .flatMap { gunzipResult =>
            gunzipResult.content
          }
          .compile
          .toVector
          .assertEquals(s.getBytes.toVector)
    }
  }

  test("gzip |> gunzipSync ~= id") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip(
              fileName = None,
              modificationTime = None,
              comment = None,
              DeflateParams(
                bufferSize = 1024,
                header = ZLibParams.Header.GZIP,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .compile
          .to(Array)
          .map { bytes =>
            val buffer = Chunk.uint8Array(facade.zlib.gunzipSync(Chunk.array(bytes).toUint8Array))

            assertEquals(buffer.toVector, s.getBytes.toVector)
          }
    }
  }

  test("unix.gzip |> gunzip") {
    val expectedContent = "fs2.compress implementing RFC 1952\n"
    val compressed = Array(0x1f, 0x8b, 0x08, 0x08, 0x62, 0xe9, 0x39, 0x5e, 0x00, 0x03, 0x66, 0x73,
      0x32, 0x2e, 0x63, 0x6f, 0x6d, 0x70, 0x72, 0x65, 0x73, 0x73, 0x00, 0x4b, 0x2b, 0x36, 0xd2,
      0x4b, 0xce, 0xcf, 0x2d, 0x28, 0x4a, 0x2d, 0x2e, 0x56, 0xc8, 0xcc, 0x2d, 0xc8, 0x49, 0xcd,
      0x4d, 0xcd, 0x2b, 0xc9, 0xcc, 0x4b, 0x57, 0x08, 0x72, 0x73, 0x56, 0x30, 0xb4, 0x34, 0x35,
      0xe2, 0x02, 0x00, 0x57, 0xb3, 0x5e, 0x6d, 0x23, 0x00, 0x00, 0x00).map(_.toByte)
    Stream
      .chunk(Chunk.array(compressed))
      .through(
        Compression[IO].gunzip()
      )
      .flatMap { gunzipResult =>
        gunzipResult.content
      }
      .compile
      .toVector
      .map(vector => new String(vector.toArray, StandardCharsets.US_ASCII))
      .assertEquals(expectedContent)
  }

}
