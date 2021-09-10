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
        val expected = new String(inflateStream(deflated, false))
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

}
