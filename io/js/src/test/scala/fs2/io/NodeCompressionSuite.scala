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
import fs2.internal.jsdeps.node.bufferMod.global.Buffer
import fs2.internal.jsdeps.node.zlibMod
import fs2.io.NodeCompression._
import org.scalacheck.effect.PropF.forAllF
import scodec.bits.{ByteVector, crc}

import java.util.concurrent.TimeUnit
import java.util.zip._
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.typedarray._
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.effect.PropF.forAllF

class NodeCompressionSuite extends Fs2Suite {

  implicit val zlibHeaders: Arbitrary[ZLibParams.Header] = Arbitrary(
    Gen.oneOf(
      ZLibParams.Header.ZLIB,
      ZLibParams.Header.GZIP
    )
  )

  implicit val juzDeflaterLevels: Arbitrary[DeflateParams.Level] = Arbitrary(
    Gen.oneOf(
      DeflateParams.Level.DEFAULT,
      DeflateParams.Level.BEST_SPEED,
      DeflateParams.Level.BEST_COMPRESSION,
      DeflateParams.Level.NO_COMPRESSION,
      DeflateParams.Level.ZERO,
      DeflateParams.Level.ONE,
      DeflateParams.Level.TWO,
      DeflateParams.Level.THREE,
      DeflateParams.Level.FOUR,
      DeflateParams.Level.FIVE,
      DeflateParams.Level.SIX,
      DeflateParams.Level.SEVEN,
      DeflateParams.Level.EIGHT,
      DeflateParams.Level.NINE
    )
  )

  implicit val juzDeflaterStrategies: Arbitrary[DeflateParams.Strategy] = Arbitrary(
    Gen.oneOf(
      DeflateParams.Strategy.DEFAULT,
      DeflateParams.Strategy.BEST_SPEED,
      DeflateParams.Strategy.BEST_COMPRESSION,
      DeflateParams.Strategy.FILTERED,
      DeflateParams.Strategy.HUFFMAN_ONLY
    )
  )

  implicit val juzDeflaterFlushModes: Arbitrary[DeflateParams.FlushMode] = Arbitrary(
    Gen.oneOf(
      DeflateParams.FlushMode.DEFAULT,
      DeflateParams.FlushMode.BEST_SPEED,
      DeflateParams.FlushMode.BEST_COMPRESSION,
      DeflateParams.FlushMode.NO_FLUSH,
      DeflateParams.FlushMode.SYNC_FLUSH,
      DeflateParams.FlushMode.FULL_FLUSH
    )
  )

  def getBytes(s: String): Array[Byte] =
    s.getBytes

  def deflateStream(b: Array[Byte], level: Int, strategy: Int, nowrap: Boolean): Array[Byte] =
    zlibMod
      .deflateSync(Buffer.from(b.toJSArray.asInstanceOf[js.Array[Double]]))
      .asInstanceOf[Int8Array]
      .toArray

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] =
    zlibMod
      .inflateSync(Buffer.from(b.toJSArray.asInstanceOf[js.Array[Double]]))
      .asInstanceOf[Int8Array]
      .toArray

  test("gzip |> gunzip ~= id") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS))
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip2(
              fileName = Some(s),
              modificationTime = Some(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS)),
              comment = Some(s),
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
            assertEquals(gunzipResult.fileName, expectedFileName)
            assertEquals(gunzipResult.comment, expectedComment)
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTimeEpoch, expectedMTime)
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
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS))
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip2(
              fileName = Some(s),
              modificationTime = Some(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS)),
              comment = Some(s),
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
            assertEquals(gunzipResult.fileName, expectedFileName)
            assertEquals(gunzipResult.comment, expectedComment)
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTimeEpoch, expectedMTime)
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
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS))
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip2(
              fileName = Some(s),
              modificationTime = Some(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS)),
              comment = Some(s),
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
            assertEquals(gunzipResult.fileName, expectedFileName)
            assertEquals(gunzipResult.comment, expectedComment)
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTimeEpoch, expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .assertEquals(s.getBytes.toVector)
    }
  }

  test("gzip.compresses input") {
    val uncompressed =
      getBytes(""""
                 |"A type system is a tractable syntactic method for proving the absence
                 |of certain program behaviors by classifying phrases according to the
                 |kinds of values they compute."
                 |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
    Stream
      .chunk[IO, Byte](Chunk.array(uncompressed))
      .through(Compression[IO].gzip2(2048))
      .compile
      .toVector
      .map(compressed => assert(compressed.length < uncompressed.length))
  }

  test("gzip.compresses input, with FLG.FHCRC set") {
    Stream
      .chunk[IO, Byte](Chunk.array(getBytes("Foo")))
      .through(
        Compression[IO].gzip2(
          fileName = None,
          modificationTime = None,
          comment = None,
          deflateParams = DeflateParams.apply(
            bufferSize = 1024 * 32,
            header = ZLibParams.Header.GZIP,
            level = DeflateParams.Level.DEFAULT,
            strategy = DeflateParams.Strategy.DEFAULT,
            flushMode = DeflateParams.FlushMode.DEFAULT,
            fhCrcEnabled = true
          )
        )
      )
      .compile
      .toVector
      .map { compressed =>
        val headerBytes = ByteVector(compressed.take(10))
        val crc32 = crc.crc32(headerBytes.toBitVector).toByteArray
        val expectedCrc16 = crc32.reverse.take(2).toVector
        val actualCrc16 = compressed.drop(10).take(2)
        assertEquals(actualCrc16, expectedCrc16)
      }
  }

  test("gunzip limit fileName and comment length") {
    val longString: String =
      Array
        .fill(1024 * 1024 + 1)("x")
        .mkString(
          ""
        ) // max(classic.fileNameBytesSoftLimit, classic.fileCommentBytesSoftLimit) + 1
    val expectedFileName = Option(toEncodableFileName(longString))
    val expectedComment = Option(toEncodableComment(longString))
    Stream
      .chunk(Chunk.empty[Byte])
      .through(Compression[IO].gzip2(8192, fileName = Some(longString), comment = Some(longString)))
      .chunkLimit(1)
      .unchunks // ensure chunk sizes are less than file name and comment size soft limits
      .through(Compression[IO].gunzip(8192))
      .flatMap { gunzipResult =>
        assert(
          gunzipResult.fileName
            .map(_.length)
            .getOrElse(0) < expectedFileName.map(_.length).getOrElse(0)
        )
        assert(
          gunzipResult.comment
            .map(_.length)
            .getOrElse(0) < expectedComment.map(_.length).getOrElse(0)
        )
        gunzipResult.content
      }
      .compile
      .last
      .assertEquals(None)
  }

  test("unix.gzip |> gunzip") {
    val expectedContent = "fs2.compress implementing RFC 1952\n"
    val expectedFileName = Option(toEncodableFileName("fs2.compress"))
    val expectedComment = Option.empty[String]
    val expectedMTime = Option(FiniteDuration(6312408098402L, TimeUnit.SECONDS))
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
        assertEquals(gunzipResult.fileName, expectedFileName)
        assertEquals(gunzipResult.comment, expectedComment)
        assertEquals(gunzipResult.modificationTimeEpoch, expectedMTime)
        gunzipResult.content
      }
      .compile
      .toVector
      .map(vector => new String(vector.toArray /*, StandardCharsets.US_ASCII*/ ))
      .assertEquals(expectedContent)
  }

  test("gzip and gunzip are reusable") {
    val bytesIn: Int = 1024 * 1024
    val chunkSize = 1024
    val gzipStream = Compression[IO].gzip2(bufferSize = chunkSize)
    val gunzipStream = Compression[IO].gunzip(bufferSize = chunkSize)
    val stream = Stream
      .chunk[IO, Byte](Chunk.array(1.to(bytesIn).map(_.toByte).toArray))
      .through(gzipStream)
      .through(gunzipStream)
      .flatMap(_.content)
    for {
      first <-
        stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
      second <-
        stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
    } yield assertEquals(first, second)
  }

  def toEncodableFileName(fileName: String): String =
    new String(
      fileName
        .replaceAll("\u0000", "_")
        .getBytes( /*StandardCharsets.ISO_8859_1*/ )
      /*StandardCharsets.ISO_8859_1*/
    )

  def toEncodableComment(comment: String): String =
    new String(
      comment.replaceAll("\u0000", " ").getBytes( /*StandardCharsets.ISO_8859_1*/ )
      /*StandardCharsets.ISO_8859_1*/
    )

}
