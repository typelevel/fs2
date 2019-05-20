package fs2

import fs2.Stream._
import cats.effect._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{
  Deflater,
  DeflaterOutputStream,
  GZIPInputStream,
  Inflater,
  InflaterOutputStream
}

import scala.collection.mutable

import compress._

class CompressSpec extends Fs2Spec {

  def getBytes(s: String): Array[Byte] =
    s.getBytes

  def deflateStream(b: Array[Byte], level: Int, nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val deflaterStream =
      new DeflaterOutputStream(byteArrayStream, new Deflater(level, nowrap))
    deflaterStream.write(b)
    deflaterStream.close()
    byteArrayStream.toByteArray()
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val inflaterStream =
      new InflaterOutputStream(byteArrayStream, new Inflater(nowrap))
    inflaterStream.write(b)
    inflaterStream.close()
    byteArrayStream.toByteArray()
  }

  "Compress" - {

    "deflate input" in forAll(strings, intsBetween(0, 9), booleans) {
      (s: String, level: Int, nowrap: Boolean) =>
        val expected = deflateStream(getBytes(s), level, nowrap).toVector
        val actual = Stream
          .chunk(Chunk.bytes(getBytes(s)))
          .through(
            deflate(
              level = level,
              nowrap = nowrap
            ))
          .toVector

        actual should equal(expected)
    }

    "inflate input" in forAll(strings, intsBetween(0, 9), booleans) {
      (s: String, level: Int, nowrap: Boolean) =>
        val expectedDeflated = deflateStream(getBytes(s), level, nowrap)
        val actualDeflated = Stream
          .chunk(Chunk.bytes(getBytes(s)))
          .through(
            deflate(
              level = level,
              nowrap = nowrap
            ))
          .toVector

        def expectEqual(expected: Array[Byte], actual: Array[Byte]) = {
          val expectedInflated = inflateStream(expected, nowrap).toVector
          val actualInflated = Stream
            .chunk(Chunk.bytes(actual))
            .covary[Fallible]
            .through(inflate(nowrap = nowrap))
            .compile
            .toVector
          actualInflated should equal(Right(expectedInflated))
        }

        expectEqual(actualDeflated.toArray, expectedDeflated.toArray)
        expectEqual(expectedDeflated.toArray, actualDeflated.toArray)
    }

    "deflate |> inflate ~= id" in forAll { s: Stream[Pure, Byte] =>
      s.covary[IO]
        .through(compress.deflate())
        .through(compress.inflate())
        .compile
        .toVector
        .asserting(_ shouldBe s.toVector)
    }

    "deflate.compresses input" in {
      val uncompressed =
        getBytes(""""
          |"A type system is a tractable syntactic method for proving the absence
          |of certain program behaviors by classifying phrases according to the
          |kinds of values they compute."
          |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
      val compressed =
        Stream.chunk(Chunk.bytes(uncompressed)).through(deflate(9)).toVector

      compressed.length should be < uncompressed.length
    }

    "gzip |> gunzip ~= id" in forAll { s: Stream[Pure, Byte] =>
      s.covary[IO]
        .through(compress.gzip[IO](8192))
        .through(compress.gunzip[IO](8192))
        .compile
        .toVector
        .asserting(_ shouldBe s.toVector)
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, compression larger)" in forAll {
      s: Stream[Pure, Byte] =>
        s.covary[IO]
          .through(compress.gzip[IO](1031))
          .through(compress.gunzip[IO](509))
          .compile
          .toVector
          .asserting(_ shouldBe s.toVector)
    }

    "gzip |> gunzip ~= id (mutually prime chunk sizes, decompression larger)" in forAll {
      s: Stream[Pure, Byte] =>
        s.covary[IO]
          .through(compress.gzip[IO](509))
          .through(compress.gunzip[IO](1031))
          .compile
          .toVector
          .asserting(_ shouldBe s.toVector)
    }

    "gzip |> GZIPInputStream ~= id" in forAll { s: Stream[Pure, Byte] =>
      val bytes = s
        .through(compress.gzip(1024))
        .compile
        .to[Array]

      val bis = new ByteArrayInputStream(bytes)
      val gzis = new GZIPInputStream(bis)

      val buffer = mutable.ArrayBuffer[Byte]()
      var read = gzis.read()
      while (read >= 0) {
        buffer += read.toByte
        read = gzis.read()
      }

      buffer.toVector shouldBe s.toVector
    }

    "gzip.compresses input" in {
      val uncompressed =
        getBytes(""""
          |"A type system is a tractable syntactic method for proving the absence
          |of certain program behaviors by classifying phrases according to the
          |kinds of values they compute."
          |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
      val compressed = Stream
        .chunk(Chunk.bytes(uncompressed))
        .through(gzip(2048))
        .compile
        .toVector

      compressed.length should be < uncompressed.length
    }
  }
}
