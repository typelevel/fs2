package fs2

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

import cats.effect._
import fs2.compression._
import org.scalatest.prop.Generator

import scala.collection.mutable

class CompressionSpec extends Fs2Spec {
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

  val zlibHeaders: Generator[ZLibParams.Header] = specificValues(
    ZLibParams.Header.ZLIB,
    ZLibParams.Header.GZIP
  )

  val juzDeflaterLevels: Generator[JavaUtilZipDeflaterParams.Level] = specificValues(
    JavaUtilZipDeflaterParams.Level.DEFAULT,
    JavaUtilZipDeflaterParams.Level.BEST_SPEED,
    JavaUtilZipDeflaterParams.Level.BEST_COMPRESSION,
    JavaUtilZipDeflaterParams.Level.NO_COMPRESSION,
    JavaUtilZipDeflaterParams.Level.ZERO,
    JavaUtilZipDeflaterParams.Level.ONE,
    JavaUtilZipDeflaterParams.Level.TWO,
    JavaUtilZipDeflaterParams.Level.THREE,
    JavaUtilZipDeflaterParams.Level.FOUR,
    JavaUtilZipDeflaterParams.Level.FIVE,
    JavaUtilZipDeflaterParams.Level.SIX,
    JavaUtilZipDeflaterParams.Level.SEVEN,
    JavaUtilZipDeflaterParams.Level.EIGHT,
    JavaUtilZipDeflaterParams.Level.NINE
  )

  val juzDeflaterStrategies: Generator[JavaUtilZipDeflaterParams.Strategy] = specificValues(
    JavaUtilZipDeflaterParams.Strategy.DEFAULT,
    JavaUtilZipDeflaterParams.Strategy.BEST_SPEED,
    JavaUtilZipDeflaterParams.Strategy.BEST_COMPRESSION,
    JavaUtilZipDeflaterParams.Strategy.FILTERED,
    JavaUtilZipDeflaterParams.Strategy.HUFFMAN_ONLY
  )

  val juzDeflaterFlushModes: Generator[JavaUtilZipDeflaterParams.FlushMode] = specificValues(
    JavaUtilZipDeflaterParams.FlushMode.DEFAULT,
    JavaUtilZipDeflaterParams.FlushMode.BEST_SPEED,
    JavaUtilZipDeflaterParams.FlushMode.BEST_COMPRESSION,
    JavaUtilZipDeflaterParams.FlushMode.NO_FLUSH,
    JavaUtilZipDeflaterParams.FlushMode.SYNC_FLUSH,
    JavaUtilZipDeflaterParams.FlushMode.FULL_FLUSH
  )

  "Compression" - {
    "deflate input" in forAll(
      strings,
      intsBetween(0, 9),
      specificValues(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY),
      booleans
    ) { (s: String, level: Int, strategy: Int, nowrap: Boolean) =>
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
        .asserting(actual => assert(actual == expected))
    }

    "inflate input" in forAll(
      strings,
      booleans,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes
    ) {
      (
          s: String,
          nowrap: Boolean,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
              new JavaUtilZipDeflaterParams(
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
              .asserting(actual => assert(actual == expected))
          }
    }

    "inflate input (deflated larger than inflated)" in {
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
            new JavaUtilZipDeflaterParams(
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
            .asserting { actual =>
              val eStr = new String(expected.toArray)
              val aStr = new String(actual.toArray)
              assert(aStr == eStr)
            }
        }
    }

    "deflate |> inflate ~= id" in forAll(
      strings,
      booleans,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes
    ) {
      (
          s: String,
          nowrap: Boolean,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode
      ) =>
        Stream
          .chunk[IO, Byte](Chunk.bytes(getBytes(s)))
          .rechunkRandomlyWithSeed(0.1, 2)(System.nanoTime())
          .through(
            deflate(
              new JavaUtilZipDeflaterParams(
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
          .asserting(it => assert(it.sameElements(getBytes(s))))
    }

    "deflate.compresses input" in {
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
        .asserting(compressed => assert(compressed.length < uncompressed.length))
    }

    "deflate and inflate are reusable" in {
      val bytesIn: Int = 1024 * 1024
      val chunkSize = 1024
      val deflater = deflate[IO](bufferSize = chunkSize)
      val inflater = inflate[IO](bufferSize = chunkSize)
      val stream = Stream
        .chunk[IO, Byte](Chunk.Bytes(1.to(bytesIn).map(_.toByte).toArray))
        .through(deflater)
        .through(inflater)
      for {
        first <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
        second <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
      } yield {
        assert(first == second)
      }
    }

    "gzip |> gunzip ~= id" in forAll(
      strings,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes,
      intsBetween(0, Int.MaxValue)
    ) {
      (
          s: String,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode,
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
              new JavaUtilZipDeflaterParams(
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
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, compression larger)" in forAll(
      strings,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes,
      intsBetween(0, Int.MaxValue)
    ) {
      (
          s: String,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode,
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
              new JavaUtilZipDeflaterParams(
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
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, decompression larger)" in forAll(
      strings,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes,
      intsBetween(0, Int.MaxValue)
    ) {
      (
          s: String,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode,
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
              new JavaUtilZipDeflaterParams(
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
          .asserting(bytes => assert(bytes == s.getBytes.toSeq))
    }

    "gzip |> GZIPInputStream ~= id" in forAll(
      strings,
      juzDeflaterLevels,
      juzDeflaterStrategies,
      juzDeflaterFlushModes,
      intsBetween(0, Int.MaxValue)
    ) {
      (
          s: String,
          level: JavaUtilZipDeflaterParams.Level,
          strategy: JavaUtilZipDeflaterParams.Strategy,
          flushMode: JavaUtilZipDeflaterParams.FlushMode,
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
              new JavaUtilZipDeflaterParams(
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
          .asserting { bytes =>
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

    "gzip.compresses input" in {
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
        .asserting(compressed => assert(compressed.length < uncompressed.length))
    }

    "gunzip limit fileName and comment length" in {
      val longString
          : String = Array.fill(1024 * 1024 + 1)("x").mkString("") // max(classic.fileNameBytesSoftLimit, classic.fileCommentBytesSoftLimit) + 1
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
        .asserting(vector => assert(vector.isEmpty))
    }

    "unix.gzip |> gunzip" in {
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
        .asserting { vector =>
          assert(new String(vector.toArray, StandardCharsets.US_ASCII) == expectedContent)
        }
    }

    "gzip and gunzip are reusable" in {
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
        first <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
        second <- stream
          .fold(Vector.empty[Byte]) { case (vector, byte) => vector :+ byte }
          .compile
          .last
      } yield {
        assert(first == second)
      }
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
}
