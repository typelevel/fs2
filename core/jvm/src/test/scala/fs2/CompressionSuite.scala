package fs2

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

import cats.effect._
import fs2.compression._

import org.scalacheck.{Arbitrary, Gen}

import scala.collection.mutable

class CompressionSpec extends Fs2Suite {
  def getBytes(s: String): Array[Byte] =
    s.getBytes

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
    forAllAsync { (s: String, level0: Int, strategy0: Int, nowrap: Boolean) =>
      val level = (level0 % 10).abs
      val strategy = Array(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)(
        (strategy0 % 3).abs
      )
      val expected = deflateStream(getBytes(s), level, strategy, nowrap).toVector
      Stream
        .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
        .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
        .through(
          deflate(
            level = level,
            strategy = strategy,
            nowrap = nowrap
          )
        )
        .compile
        .toVector
        .map(actual => assert(actual == expected))
    }
  }

  test("inflate input") {
    forAllAsync {
      (
          s: String,
          nowrap: Boolean,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
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
              .chunk[IO, Byte](Chunk.bytes(deflated))
              .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
              .through(inflate(nowrap = nowrap))
              .compile
              .toVector
              .map(actual => assert(actual == expected))
          }
    }
  }

  test("inflate input (deflated larger than inflated)") {
    Stream
      .chunk[IO, Byte](
        Chunk.bytes(
          getBytes(
            "꒔諒ᇂ즆ᰃ遇ኼ㎐만咘똠ᯈ䕍쏮쿻ࣇ㦲䷱瘫椪⫐褽睌쨘꛹騏蕾☦余쒧꺠ܝ猸b뷈埣ꂓ琌ཬ隖㣰忢鐮橀쁚誅렌폓㖅ꋹ켗餪庺Đ懣㫍㫌굦뢲䅦苮Ѣқ闭䮚ū﫣༶漵>껆拦휬콯耙腒䔖돆圹Ⲷ曩ꀌ㒈"
          )
        )
      )
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(
        deflate(
          DeflateParams(
            header = ZLibParams.Header.ZLIB
          )
        )
      )
      .compile
      .to(Array)
      .flatMap { deflated =>
        val expected = inflateStream(deflated, false).toVector
        Stream
          .chunk[IO, Byte](Chunk.bytes(deflated))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(inflate(nowrap = false))
          .compile
          .toVector
          .map { actual =>
            val eStr = new String(expected.toArray)
            val aStr = new String(actual.toArray)
            assert(aStr == eStr)
          }
      }
  }

  test("deflate |> inflate ~= id") {
    forAllAsync {
      (
          s: String,
          nowrap: Boolean,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
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
          .through(inflate(nowrap = nowrap))
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
      .chunk[IO, Byte](Chunk.bytes(uncompressed))
      .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
      .through(deflate(level = 9))
      .compile
      .toVector
      .map(compressed => assert(compressed.length < uncompressed.length))
  }

  test("deflate and inflate are reusable") {
    val bytesIn: Int = 1024 * 1024
    val chunkSize = 1024
    val deflater = deflate[IO](bufferSize = chunkSize)
    val inflater = inflate[IO](bufferSize = chunkSize)
    val stream = Stream
      .chunk[IO, Byte](Chunk.Bytes(1.to(bytesIn).map(_.toByte).toArray))
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
    } yield assert(first == second)
  }

  test("gzip |> gunzip ~= id".flaky) {
    forAllAsync {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              fileName = Some(s),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
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
            gunzip[IO](8192)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .map(bytes => assert(bytes == s.getBytes.toSeq))
    }
  }

  test("gzip |> gunzip ~= id (mutually prime chunk sizes, compression larger)".flaky) {
    forAllAsync {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              fileName = Some(s),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
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
            gunzip[IO](509)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .map(bytes => assert(bytes == s.getBytes.toSeq))
    }
  }

  test("gzip |> gunzip ~= id (mutually prime chunk sizes, decompression larger)".flaky) {
    forAllAsync {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        val expectedFileName = Option(toEncodableFileName(s))
        val expectedComment = Option(toEncodableComment(s))
        val expectedMTime = Option(Instant.ofEpochSecond(epochSeconds))
        Stream
          .chunk(Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip[IO](
              fileName = Some(s),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
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
            gunzip[IO](1031)
          )
          .flatMap { gunzipResult =>
            assert(gunzipResult.fileName == expectedFileName)
            assert(gunzipResult.comment == expectedComment)
            if (epochSeconds != 0) assert(gunzipResult.modificationTime == expectedMTime)
            gunzipResult.content
          }
          .compile
          .toVector
          .map(bytes => assert(bytes == s.getBytes.toSeq))
    }
  }

  test("gzip |> GZIPInputStream ~= id") {
    forAllAsync {
      (
          s: String,
          level: DeflateParams.Level,
          strategy: DeflateParams.Strategy,
          flushMode: DeflateParams.FlushMode,
          epochSeconds: Int
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(s.getBytes))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            gzip(
              fileName = Some(s),
              modificationTime = Some(Instant.ofEpochSecond(epochSeconds)),
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

            assert(buffer.toVector == s.getBytes.toVector)
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
      .chunk[IO, Byte](Chunk.bytes(uncompressed))
      .through(gzip(2048))
      .compile
      .toVector
      .map(compressed => assert(compressed.length < uncompressed.length))
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
      .through(gzip[IO](8192, fileName = Some(longString), comment = Some(longString)))
      .unchunk // ensure chunk sizes are less than file name and comment size soft limits
      .through(gunzip[IO](8192))
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
      .toVector
      .map(vector => assert(vector.isEmpty))
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
      .chunk(Chunk.bytes(compressed))
      .through(
        gunzip[IO]()
      )
      .flatMap { gunzipResult =>
        assert(gunzipResult.fileName == expectedFileName)
        assert(gunzipResult.comment == expectedComment)
        assert(gunzipResult.modificationTime == expectedMTime)
        gunzipResult.content
      }
      .compile
      .toVector
      .map { vector =>
        assert(new String(vector.toArray, StandardCharsets.US_ASCII) == expectedContent)
      }
  }

  test("gzip and gunzip are reusable") {
    val bytesIn: Int = 1024 * 1024
    val chunkSize = 1024
    val gzipStream = gzip[IO](bufferSize = chunkSize)
    val gunzipStream = gunzip[IO](bufferSize = chunkSize)
    val stream = Stream
      .chunk[IO, Byte](Chunk.Bytes(1.to(bytesIn).map(_.toByte).toArray))
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
    } yield assert(first == second)
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
