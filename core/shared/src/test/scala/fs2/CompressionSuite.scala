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
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.effect.PropF.forAllF
import scodec.bits.{ByteVector, crc}

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

abstract class CompressionSuite(implicit compression: Compression[IO]) extends Fs2Suite {
  def getBytes(s: String): Array[Byte] =
    s.getBytes

  def deflateStream(b: Array[Byte], level: Int, strategy: Int, nowrap: Boolean): Array[Byte]

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte]

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

  test("deflate input") {
    forAllF { (s: String, level0: Int, strategy0: Int, nowrap: Boolean) =>
      val level = (level0 % 10).abs
      val strategy = Array(
        DeflateParams.Strategy.DEFAULT.juzDeflaterStrategy,
        DeflateParams.Strategy.FILTERED.juzDeflaterStrategy,
        DeflateParams.Strategy.HUFFMAN_ONLY.juzDeflaterStrategy
      )(
        (strategy0 % 3).abs
      )
      val expected = deflateStream(getBytes(s), level, strategy, nowrap).toVector
      Stream
        .chunk[IO, Byte](Chunk.array(getBytes(s)))
        .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
        .through(
          Compression[IO].deflate(
            DeflateParams(
              level = DeflateParams.Level(level),
              strategy = DeflateParams.Strategy(strategy),
              header = ZLibParams.Header(nowrap)
            )
          )
        )
        .compile
        .toVector
        .assertEquals(expected)
    }
  }

  test("inflate input") {
    forAllF {
      (
          s: String,
          nowrap: Boolean,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.array(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].deflate(
              DeflateParams(
                bufferSize = 32 * 1024,
                header = if (nowrap) ZLibParams.Header.GZIP else ZLibParams.Header.ZLIB,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .compile
          .to(Array)
          .flatMap { deflated =>
            val expected = inflateStream(deflated, nowrap).toVector
            Stream
              .chunk[IO, Byte](Chunk.array(deflated))
              .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
              .through(Compression[IO].inflate(InflateParams(header = ZLibParams.Header(nowrap))))
              .compile
              .toVector
              .assertEquals(expected)
          }
    }
  }

  test("inflate input (deflated larger than inflated)") {
    Stream
      .chunk[IO, Byte](
        Chunk.array(
          getBytes(
            "꒔諒ᇂ즆ᰃ遇ኼ㎐만咘똠ᯈ䕍쏮쿻ࣇ㦲䷱瘫椪⫐褽睌쨘꛹騏蕾☦余쒧꺠ܝ猸b뷈埣ꂓ琌ཬ隖㣰忢鐮橀쁚誅렌폓㖅ꋹ켗餪庺Đ懣㫍㫌굦뢲䅦苮Ѣқ闭䮚ū﫣༶漵>껆拦휬콯耙腒䔖돆圹Ⲷ曩ꀌ㒈"
          )
        )
      )
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(
        Compression[IO].deflate(
          DeflateParams(
            header = ZLibParams.Header.ZLIB
          )
        )
      )
      .compile
      .to(Array)
      .flatMap { deflated =>
        val expected = new String(inflateStream(deflated, nowrap = false))
        Stream
          .chunk[IO, Byte](Chunk.array(deflated))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(Compression[IO].inflate(InflateParams(header = ZLibParams.Header(false))))
          .compile
          .to(Array)
          .map(new String(_))
          .assertEquals(expected)
      }
  }

  test("deflate |> inflate ~= id") {
    forAllF {
      (
          s: String,
          nowrap: Boolean,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.array(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].deflate(
              DeflateParams(
                bufferSize = 32 * 1024,
                header = if (nowrap) ZLibParams.Header.GZIP else ZLibParams.Header.ZLIB,
                level = level,
                strategy = strategy,
                flushMode = flushMode
              )
            )
          )
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(Compression[IO].inflate(InflateParams(header = ZLibParams.Header(nowrap))))
          .compile
          .to(Array)
          .map(it => assert(it.sameElements(getBytes(s))))
    }
  }

  test("deflate.compresses input") {
    val uncompressed =
      getBytes(""""
                   |"A type system is a tractable syntactic method for proving the absence
                   |of certain program behaviors by classifying phrases according to the
                   |kinds of values they compute."
                   |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
    Stream
      .chunk[IO, Byte](Chunk.array(uncompressed))
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(Compression[IO].deflate(DeflateParams(level = DeflateParams.Level.NINE)))
      .compile
      .toVector
      .map(compressed => assert(compressed.length < uncompressed.length))
  }

  test("deflate and inflate are reusable") {
    val bytesIn: Int = 1024 * 1024
    val chunkSize = 1024
    val deflater = Compression[IO].deflate(DeflateParams(bufferSize = chunkSize))
    val inflater = Compression[IO].inflate(InflateParams(bufferSize = chunkSize))
    val stream = Stream
      .chunk[IO, Byte](Chunk.array(1.to(bytesIn).map(_.toByte).toArray))
      .through(deflater)
      .through(inflater)
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

  test("deflate |> inflate ~= id") {
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
            Compression[IO].deflate(
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
            Compression[IO].inflate(
              InflateParams(
                bufferSize = 8192,
                header = ZLibParams.Header.GZIP
              )
            )
          )
          .compile
          .toVector
          .map { result =>
            assertEquals(result, s.getBytes.toVector)
            ()
          }
    }
  }

  test("empty.gz |> gunzip") {

    val bytes = Array(
      0x1f, 0x8b, 0x08, 0x08, 0x0f, 0x85, 0xc7, 0x61, 0x00, 0x03, 0x65, 0x6d, 0x70, 0x74, 0x79,
      0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ).map(_.toByte)
    val expectedBytes = Array.empty[Byte]

    val expectedFileName = Option(toEncodableFileName("empty"))
    Stream
      .chunk(Chunk.array(bytes))
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(
        Compression[IO].gunzip(8192)
      )
      .flatMap { gunzipResult =>
        assertEquals(gunzipResult.fileName, expectedFileName)
        gunzipResult.content
      }
      .compile
      .toVector
      .assertEquals(expectedBytes.toVector)
  }

  test("hello-compression.gz |> gunzip") {

    val bytes = Array(
      0x1f, 0x8b, 0x08, 0x08, 0x99, 0x8a, 0xc7, 0x61, 0x00, 0x03, 0x68, 0x65, 0x6c, 0x6c, 0x6f,
      0x2d, 0x63, 0x6f, 0x6d, 0x70, 0x72, 0x65, 0x73, 0x73, 0x69, 0x6f, 0x6e, 0x2e, 0x6a, 0x73,
      0x6f, 0x6e, 0x00, 0xab, 0x56, 0xca, 0x48, 0xcd, 0xc9, 0xc9, 0x57, 0xb2, 0x52, 0x4a, 0xce,
      0xcf, 0x2d, 0x28, 0x4a, 0x2d, 0x2e, 0xce, 0xcc, 0xcf, 0x53, 0xaa, 0xe5, 0x02, 0x00, 0x47,
      0x6f, 0xf6, 0xe9, 0x18, 0x00, 0x00, 0x00
    ).map(_.toByte)
    val expectedBytes =
      """{"hello":"compression"}
        |""".stripMargin.getBytes

    val expectedFileName = Option(toEncodableFileName("hello-compression.json"))
    Stream
      .chunk(Chunk.array(bytes))
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(
        Compression[IO].gunzip(8192)
      )
      .flatMap { gunzipResult =>
        assertEquals(gunzipResult.fileName, expectedFileName)
        gunzipResult.content
      }
      .compile
      .toVector
      .assertEquals(expectedBytes.toVector)
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
        val expectedMTime = Option(FiniteDuration(epochSeconds.toLong, TimeUnit.SECONDS))
        Stream
          .chunk(Chunk.array(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            Compression[IO].gzip(
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationEpochTime, expectedMTime)
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
            Compression[IO].gzip(
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationEpochTime, expectedMTime)
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
            Compression[IO].gzip(
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
            if (epochSeconds > 0) assertEquals(gunzipResult.modificationEpochTime, expectedMTime)
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
      .through(Compression[IO].gzip(2048))
      .compile
      .toVector
      .map(compressed => assert(compressed.length < uncompressed.length))
  }

  test("gzip.compresses input, with FLG.FHCRC set") {
    Stream
      .chunk[IO, Byte](Chunk.array(getBytes("Foo")))
      .through(
        Compression[IO].gzip(
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
        .fill(1034 * 1024)("x")
        .mkString(
          ""
        ) // max(classic.fileNameBytesSoftLimit, classic.fileCommentBytesSoftLimit) + 1
    val expectedFileName = Option(toEncodableFileName(longString))
    val expectedComment = Option(toEncodableComment(longString))

    Stream
      .chunk(Chunk.empty[Byte])
      .through(Compression[IO].gzip(8192, fileName = Some(longString), comment = Some(longString)))
      .chunkLimit(512)
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
    val expectedMTime = Option(FiniteDuration(1580853602, TimeUnit.SECONDS)) // 2020-02-04T22:00:02Z
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
        assertEquals(gunzipResult.modificationEpochTime, expectedMTime)
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
    val gzipStream = Compression[IO].gzip(bufferSize = chunkSize)
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
        .getBytes(StandardCharsets.ISO_8859_1),
      StandardCharsets.ISO_8859_1
    )

  def toEncodableComment(comment: String): String =
    new String(
      comment.replaceAll("\u0000", " ").getBytes(StandardCharsets.ISO_8859_1),
      StandardCharsets.ISO_8859_1
    )

}
