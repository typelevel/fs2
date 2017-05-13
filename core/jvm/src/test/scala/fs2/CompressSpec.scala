package fs2

import fs2.Stream._

import compress._

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import java.io.ByteArrayOutputStream
import java.util.zip.{Deflater, DeflaterOutputStream, Inflater, InflaterOutputStream}

class CompressSpec extends Fs2Spec {

  def getBytes(s: String): Array[Byte] =
    s.getBytes

  def deflateStream(b: Array[Byte], level: Int, nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val deflaterStream = new DeflaterOutputStream(byteArrayStream, new Deflater(level, nowrap))
    deflaterStream.write(b)
    deflaterStream.close()
    byteArrayStream.toByteArray()
  }

  def inflateStream(b: Array[Byte], nowrap: Boolean): Array[Byte] = {
    val byteArrayStream = new ByteArrayOutputStream()
    val inflaterStream = new InflaterOutputStream(byteArrayStream, new Inflater(nowrap))
    inflaterStream.write(b)
    inflaterStream.close()
    byteArrayStream.toByteArray()
  }

  "Compress" - {

    "deflate input" in forAll(arbitrary[String], Gen.choose(0, 9), arbitrary[Boolean])
      { (s: String, level: Int, nowrap: Boolean) =>
        val expected = deflateStream(getBytes(s), level, nowrap).toVector
        val actual = Stream.chunk(Chunk.bytes(getBytes(s))).throughPure(deflate(
          level = level,
          nowrap = nowrap
        )).toVector

        actual should equal(expected)
      }

    "inflate input" in forAll(arbitrary[String], Gen.choose(0, 9), arbitrary[Boolean])
      { (s: String, level: Int, nowrap: Boolean) =>
        val expectedDeflated = deflateStream(getBytes(s), level, nowrap)
        val actualDeflated = Stream.chunk(Chunk.bytes(getBytes(s))).throughPure(deflate(
          level = level,
          nowrap = nowrap
        )).toVector

        def expectEqual(expected: Array[Byte], actual: Array[Byte]) = {
          val expectedInflated = inflateStream(expected, nowrap).toVector
          val actualInflated = Stream.chunk(Chunk.bytes(actual)).throughPure(inflate(nowrap = nowrap)).toVector
          actualInflated should equal(expectedInflated)
        }

        expectEqual(actualDeflated.toArray, expectedDeflated.toArray)
        expectEqual(expectedDeflated.toArray, actualDeflated.toArray)
      }

    "deflate |> inflate ~= id" in forAll { (s: PureStream[Byte]) =>
      s.get.toVector shouldBe s.get.through(compress.deflate()).through(compress.inflate()).toVector
    }

    "deflate.compresses input" in {
      val uncompressed = getBytes(
        """"
          |"A type system is a tractable syntactic method for proving the absence
          |of certain program behaviors by classifying phrases according to the
          |kinds of values they compute."
          |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
      val compressed = Stream.chunk(Chunk.bytes(uncompressed)).throughPure(deflate(9)).toVector

      compressed.length should be < uncompressed.length
    }
  }
}
