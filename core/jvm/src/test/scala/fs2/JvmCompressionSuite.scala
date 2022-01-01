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

import cats.effect._
import fs2.compression._
import org.scalacheck.effect.PropF.forAllF

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._
import scala.collection.mutable
import scodec.bits.crc
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class JvmCompressionSuite extends CompressionSuite {

  def deflateStream(b: Array[Byte], level: Int, strategy: Int, nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val deflater = new Deflater(level, nowrap)
    deflater.setStrategy(strategy)
    val deflaterStream = new DeflaterOutputStream(byteArrayStream, deflater)
    deflaterStream.write(b)
    deflaterStream.close()
    byteArrayStream.toByteArray
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val inflaterStream =
      new InflaterOutputStream(byteArrayStream, new Inflater(nowrap))
    inflaterStream.write(b)
    inflaterStream.close()
    byteArrayStream.toByteArray
  }

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
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds.toLong))
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTime, expectedMTime)
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
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds.toLong))
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTime, expectedMTime)
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
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds.toLong))
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationTime, expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .assertEquals(s.getBytes.toVector)
    }
  }

  test("gzip |> GZIPInputStream ~= id") {
    forAllF {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip2(
              fileName = Some(s),
              modificationTime = Some(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS)),
              comment = Some(s),
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
            val bis = new ByteArrayInputStream(bytes)
            val gzis = new GZIPInputStream(bis)

            val buffer = mutable.ArrayBuffer[Byte]()
            var read = gzis.read()
            while (read >= 0) {
              buffer += read.toByte
              read = gzis.read()
            }

            assertEquals(buffer.toVector, s.getBytes.toVector)
          }
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
        .fill(1024 * 1032)("x")
        .mkString(
          ""
        ) // max(classic.fileNameBytesSoftLimit, classic.fileCommentBytesSoftLimit) + 1
    val expectedFileName = Option(toEncodableFileName(longString))
    val expectedComment = Option(toEncodableComment(longString))
    Stream
      .chunk(Chunk.empty[Byte])
      .through(Compression[IO].gzip2(8192, fileName = Some(longString), comment = Some(longString)))
      .chunkLimit(1024)
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
    val expectedMTime = Option(Instant.parse("2020-02-04T22:00:02Z"))
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
        assertEquals(gunzipResult.modificationTime, expectedMTime)
        gunzipResult.content
      }
      .compile
      .toVector
      .map(vector => new String(vector.toArray, StandardCharsets.US_ASCII))
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
      fileName.replaceAll("\u0000", "_").getBytes(StandardCharsets.ISO_8859_1),
      StandardCharsets.ISO_8859_1
    )

  def toEncodableComment(comment: String): String =
    new String(
      comment.replaceAll("\u0000", " ").getBytes(StandardCharsets.ISO_8859_1),
      StandardCharsets.ISO_8859_1
    )

}
