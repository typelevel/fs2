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

import cats.syntax.all._

import java.nio.charset.Charset
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.bits._
import scodec.bits.Bases.Alphabets.Base64Url
import fs2.text._

import java.nio.charset.StandardCharsets

class TextSuite extends Fs2Suite {
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  group("utf8.decoder") {
    def utf8Bytes(s: String): Chunk[Byte] = Chunk.array(s.getBytes("UTF-8"))
    def utf8String(bs: Chunk[Byte]): String = new String(bs.toArray, "UTF-8")

    def genStringNoBom: Gen[String] = Arbitrary.arbitrary[String].filterNot(_.startsWith("\ufeff"))

    def checkChar(c: Char): Unit =
      if (c != '\ufeff')
        (1 to 6).foreach { n =>
          assertEquals(
            Stream
              .chunk(utf8Bytes(c.toString))
              .chunkLimit(n)
              .unchunks
              .through(utf8.decode)
              .toList,
            List(c.toString)
          )
        }

    def checkBytes(is: Int*): Unit =
      (1 to 6).foreach { n =>
        val bytes = Chunk.array(is.map(_.toByte).toArray)
        assertEquals(
          Stream
            .chunk(bytes)
            .chunkLimit(n)
            .unchunks
            .through(utf8.decode)
            .toList,
          List(utf8String(bytes))
        )
      }

    def checkBytes2(is: Int*): Unit = {
      val bytes = Chunk.array(is.map(_.toByte).toArray)
      assertEquals(
        Stream(bytes).unchunks.through(utf8.decode).toList.mkString,
        utf8String(bytes)
      )
    }

    property("all chars roundtrip")(forAll((c: Char) => checkChar(c)))

    test("1 byte char")(checkBytes(0x24)) // $
    test("2 byte char")(checkBytes(0xc2, 0xa2)) // ¢
    test("3 byte char")(checkBytes(0xe2, 0x82, 0xac)) // €
    test("4 byte char")(checkBytes(0xf0, 0xa4, 0xad, 0xa2))

    test("incomplete 2 byte char")(checkBytes(0xc2))
    test("incomplete 3 byte char")(checkBytes(0xe2, 0x82))
    test("incomplete 4 byte char")(checkBytes(0xf0, 0xa4, 0xad))

    property("preserves complete inputs") {
      forAll(Gen.listOf(genStringNoBom)) { (l0: List[String]) =>
        val l = l0.filter(_.nonEmpty)
        assertEquals(
          Stream(l: _*).map(utf8Bytes).unchunks.through(utf8.decode).toList,
          l
        )
        assertEquals(Stream(l: _*).map(utf8Bytes).through(utf8.decodeC).toList, l)
      }
    }

    property("utf8Encode andThen utf8.decode = id") {
      forAll(genStringNoBom) { (s: String) =>
        if (s.nonEmpty) {
          assertEquals(Stream(s).through(utf8.encodeC).through(utf8.decodeC).toList, List(s))
          assertEquals(Stream(s).through(utf8.encode).through(utf8.decode).toList, List(s))
        }
      }
    }

    property("1 byte sequences") {
      forAll(genStringNoBom) { (s: String) =>
        assertEquals(
          Stream
            .chunk(utf8Bytes(s))
            .chunkLimit(1)
            .unchunks
            .through(utf8.decode)
            .compile
            .string,
          s
        )
      }
    }

    property("n byte sequences") {
      forAll(Gen.alphaStr, Gen.chooseNum(1, 9)) { (s: String, n: Int) =>
        assertEquals(
          Stream
            .chunk(utf8Bytes(s))
            .chunkLimit(n)
            .unchunks
            .through(utf8.decode)
            .compile
            .string,
          s
        )
      }
    }

    group("handles byte order mark") {
      val bom = Chunk[Byte](0xef.toByte, 0xbb.toByte, 0xbf.toByte)
      property("single chunk") {
        forAll(genStringNoBom) { (s: String) =>
          val c = bom ++ utf8Bytes(s)
          assertEquals(Stream.chunk(c).through(text.utf8.decode).compile.string, s)
        }
      }
      property("spanning chunks") {
        forAll(genStringNoBom) { (s: String) =>
          forAll(Gen.chooseNum(0, s.length + bom.size, 0, 1, 2, 3, 4)) { splitIndex =>
            val (c1, c2) = (bom ++ utf8Bytes(s)).splitAt(splitIndex)
            assertEquals(Stream(c1, c2).unchunks.through(text.utf8.decode).compile.string, s)
          }

        }
      }
      test("does not force buffering") {
        val s1 = Stream(97, 98, 99).map(_.toByte).chunkLimit(1).unchunks
        assertEquals(s1.through(text.utf8.decode).chunks.compile.count, 3L)

        val s2 = Stream(0xef, 98, 99).map(_.toByte).chunkLimit(1).unchunks
        assertEquals(s2.through(text.utf8.decode).chunks.compile.count, 2L)

        val s3 = Stream(0xef, 0xbb, 99).map(_.toByte).chunkLimit(1).unchunks
        assertEquals(s3.through(text.utf8.decode).chunks.compile.count, 1L)
      }
    }

    group("Markus Kuhn UTF-8 stress tests") {
      // The next tests were taken from:
      // https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-test.txt

      group("2.1 - First possible sequence of a certain length") {
        test("2.1.1")(checkBytes(0x00))
        test("2.1.2")(checkBytes(0xc2, 0x80))
        test("2.1.3")(checkBytes(0xe0, 0xa0, 0x80))
        test("2.1.4")(checkBytes(0xf0, 0x90, 0x80, 0x80))
        test("2.1.5")(checkBytes2(0xf8, 0x88, 0x80, 0x80, 0x80))
        test("2.1.6")(checkBytes2(0xfc, 0x84, 0x80, 0x80, 0x80, 0x80))
      }

      group("2.2 - Last possible sequence of a certain length") {
        test("2.2.1")(checkBytes(0x7f))
        test("2.2.2")(checkBytes(0xdf, 0xbf))
        test("2.2.3")(checkBytes(0xef, 0xbf, 0xbf))
        test("2.2.4")(checkBytes(0xf7, 0xbf, 0xbf, 0xbf))
        test("2.2.5")(checkBytes2(0xfb, 0xbf, 0xbf, 0xbf, 0xbf))
        test("2.2.6")(checkBytes2(0xfd, 0xbf, 0xbf, 0xbf, 0xbf, 0xbf))
      }

      group("2.3 - Other boundary conditions") {
        test("2.3.1")(checkBytes(0xed, 0x9f, 0xbf))
        test("2.3.2")(checkBytes(0xee, 0x80, 0x80))
        test("2.3.3")(checkBytes(0xef, 0xbf, 0xbd))
        test("2.3.4")(checkBytes(0xf4, 0x8f, 0xbf, 0xbf))
        test("2.3.5")(checkBytes(0xf4, 0x90, 0x80, 0x80))
      }

      group("3.1 - Unexpected continuation bytes") {
        test("3.1.1")(checkBytes(0x80))
        test("3.1.2")(checkBytes(0xbf))
      }

      group("3.5 - Impossible bytes") {
        test("3.5.1")(checkBytes(0xfe))
        test("3.5.2")(checkBytes(0xff))
        test("3.5.3")(checkBytes2(0xfe, 0xfe, 0xff, 0xff))
      }

      group("4.1 - Examples of an overlong ASCII character") {
        test("4.1.1")(checkBytes(0xc0, 0xaf))
        test("4.1.2")(checkBytes(0xe0, 0x80, 0xaf))
        test("4.1.3")(checkBytes(0xf0, 0x80, 0x80, 0xaf))
        test("4.1.4")(checkBytes2(0xf8, 0x80, 0x80, 0x80, 0xaf))
        test("4.1.5")(checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0xaf))
      }

      group("4.2 - Maximum overlong sequences") {
        test("4.2.1")(checkBytes(0xc1, 0xbf))
        test("4.2.2")(checkBytes(0xe0, 0x9f, 0xbf))
        test("4.2.3")(checkBytes(0xf0, 0x8f, 0xbf, 0xbf))
        test("4.2.4")(checkBytes2(0xf8, 0x87, 0xbf, 0xbf, 0xbf))
        test("4.2.5")(checkBytes2(0xfc, 0x83, 0xbf, 0xbf, 0xbf, 0xbf))
      }

      group("4.3 - Overlong representation of the NUL character") {
        test("4.3.1")(checkBytes(0xc0, 0x80))
        test("4.3.2")(checkBytes(0xe0, 0x80, 0x80))
        test("4.3.3")(checkBytes(0xf0, 0x80, 0x80, 0x80))
        test("4.3.4")(checkBytes2(0xf8, 0x80, 0x80, 0x80, 0x80))
        test("4.3.5")(checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0x80))
      }

      group("5.1 - Single UTF-16 surrogates") {
        test("5.1.1")(checkBytes(0xed, 0xa0, 0x80))
        test("5.1.2")(checkBytes(0xed, 0xad, 0xbf))
        test("5.1.3")(checkBytes(0xed, 0xae, 0x80))
        test("5.1.4")(checkBytes(0xed, 0xaf, 0xbf))
        test("5.1.5")(checkBytes(0xed, 0xb0, 0x80))
        test("5.1.6")(checkBytes(0xed, 0xbe, 0x80))
        test("5.1.7")(checkBytes(0xed, 0xbf, 0xbf))
      }

      group("5.2 - Paired UTF-16 surrogates") {
        test("5.2.1")(checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xb0, 0x80))
        test("5.2.2")(checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xbf, 0xbf))
        test("5.2.3")(checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xb0, 0x80))
        test("5.2.4")(checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xbf, 0xbf))
        test("5.2.5")(checkBytes2(0xed, 0xae, 0x80, 0xed, 0xb0, 0x80))
        test("5.2.6")(checkBytes2(0xed, 0xae, 0x80, 0xed, 0xbf, 0xbf))
        test("5.2.7")(checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xb0, 0x80))
        test("5.2.8")(checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xbf, 0xbf))
      }

      group("5.3 - Other illegal code positions") {
        test("5.3.1")(checkBytes(0xef, 0xbf, 0xbe))
        test("5.3.2")(checkBytes(0xef, 0xbf, 0xbf))
      }
    }
  }

  group("lines / linesLimited") {
    def escapeCrLf(s: String): String =
      s.replaceAll("\r\n", "<CRLF>").replaceAll("\n", "<LF>").replaceAll("\r", "<CR>")

    property("newlines appear in between chunks") {
      forAll { (lines0: Stream[Pure, String]) =>
        val lines = lines0.map(escapeCrLf)
        assertEquals(lines.intersperse("\n").through(text.lines).toList, lines.toList)
        assertEquals(lines.intersperse("\r\n").through(text.lines).toList, lines.toList)
        assertEquals(lines.intersperse("\r").through(text.lines).toList, lines.toList)
      }
    }

    property("single string") {
      forAll { (lines0: Stream[Pure, String]) =>
        val lines = lines0.map(escapeCrLf)
        if (lines.toList.nonEmpty) {
          val s = lines.intersperse("\r\n").toList.mkString
          assertEquals(Stream.emit(s).through(text.lines).toList, lines.toList)
        }
      }
    }

    test("EOF") {
      List("\n", "\r\n", "\r").foreach { delimiter =>
        val s = s"a$delimiter"
        assertEquals(Stream.emit(s).through(lines).toList, List("a", ""))
      }
    }

    property("grouped in 3 character chunks") {
      forAll { (lines0: Stream[Pure, String]) =>
        val lines = lines0.map(escapeCrLf)
        val s = lines.intersperse("\r\n").toList.mkString.grouped(3).toList
        if (s.isEmpty)
          assertEquals(Stream.emits(s).through(text.lines).toList, Nil)
        else {
          assertEquals(Stream.emits(s).through(text.lines).toList, lines.toList)
          assertEquals(
            Stream.emits(s).chunkLimit(1).unchunks.through(text.lines).toList,
            lines.toList
          )
        }
      }
    }

    test("linesLimited") {
      val line = "foo" * 100
      (1 to line.length).foreach { i =>
        val stream = Stream
          .emits(line.toCharArray)
          .chunkN(i)
          .map(c => new String(c.toArray))
          .covary[Fallible]

        assert(stream.through(text.linesLimited(10)).toList.isLeft)
        assertEquals(
          stream.through(text.linesLimited(line.length)).toList,
          Right(List(line))
        )
      }
    }
  }

  property("base64.encode") {
    forAll { (bs: List[Array[Byte]]) =>
      assertEquals(
        bs.map(Chunk.array(_)).foldMap(Stream.chunk).through(text.base64.encode).compile.string,
        bs.map(ByteVector.view(_)).foldLeft(ByteVector.empty)(_ ++ _).toBase64
      )
    }
  }

  group("base64.decode") {

    property("base64.encode andThen base64.decode") {
      forAll { (bs: List[Array[Byte]], unchunked: Boolean, rechunkSeed: Long) =>
        assertEquals(
          bs.map(Chunk.array(_))
            .foldMap(Stream.chunk)
            .through(text.base64.encode)
            .through {
              // Change chunk structure to validate carries
              if (unchunked) _.chunkLimit(1).unchunks
              else _.rechunkRandomlyWithSeed(0.1, 2.0)(rechunkSeed)
            }
            .through {
              // Add some whitespace
              _.chunks
                .interleave(Stream(" ", "\r\n", "\n", "  \r\n  ").map(Chunk.singleton).repeat)
                .unchunks
            }
            .through(text.base64.decode[Fallible])
            .compile
            .to(ByteVector): Either[Throwable, ByteVector],
          Right(bs.map(ByteVector.view(_)).foldLeft(ByteVector.empty)(_ ++ _))
        )
      }
    }

    test("invalid padding") {
      assertEquals(
        Stream(hex"00deadbeef00".toBase64, "=====", hex"00deadbeef00".toBase64)
          .through(text.base64.decode[Fallible])
          .chunks
          .attempt
          .map(_.leftMap(_.getMessage))
          .compile
          .toList,
        Right(
          List(
            Right(Chunk.byteVector(hex"00deadbeef00")),
            Left(
              "Malformed padding - final quantum may optionally be padded with one or two padding characters such that the quantum is completed"
            )
          )
        )
      )
    }

    property("optional padding") {
      forAll { (bs: List[Array[Byte]]) =>
        assertEquals(
          bs.map(Chunk.array(_))
            .foldMap(Stream.chunk)
            .through(text.base64.encode)
            .map(_.takeWhile(_ != '='))
            .through(text.base64.decode[Fallible])
            .compile
            .to(ByteVector): Either[Throwable, ByteVector],
          Right(bs.map(ByteVector.view(_)).foldLeft(ByteVector.empty)(_ ++ _))
        )
      }
    }

    test("#1852") {
      val string = "0123456789012345678901234567890123456789012345678901234"
      val encoded = ByteVector.view(string.getBytes()).toBase64(Base64Url)
      val decoded = ByteVector.fromBase64(encoded, Base64Url)
      val res =
        Stream
          .emits(encoded.toSeq)
          .chunkN(5)
          .flatMap(chunk => Stream(chunk.toArray.toSeq.mkString))
          .through(text.base64.decodeWithAlphabet[Fallible](Base64Url))
          .chunks
          .fold(ByteVector.empty)(_ ++ _.toByteVector)
          .compile
          .last
      assertEquals(res, Right(decoded))
    }
  }

  group("hex") {
    def rechunkStrings[F[_]](in: Stream[F, String]): Stream[F, String] =
      in.flatMap(s => Stream.emits(s))
        .rechunkRandomly()
        .chunks
        .map(chars => new String(chars.toArray))

    property("decode") {
      forAll { (bs: List[Array[Byte]]) =>
        val byteVectors = bs.map(a => ByteVector.view(a))
        val strings = byteVectors.map(_.toHex)
        val source = Stream.emits(strings)
        val decoded =
          rechunkStrings(source).covary[Fallible].through(text.hex.decode).compile.to(ByteVector)
        assertEquals(
          decoded,
          Right(byteVectors.foldLeft(ByteVector.empty)(_ ++ _))
        )
      }
    }

    property("encode") {
      forAll { (bs: List[Array[Byte]]) =>
        assertEquals(
          Stream
            .emits(bs.map(Chunk.array(_)))
            .unchunks
            .through(text.hex.encode)
            .compile
            .string,
          bs.foldLeft(ByteVector.empty)((acc, arr) => acc ++ ByteVector.view(arr)).toHex
        )
      }
    }
  }

  group("decodeWithCharset") {
    List(
      StandardCharsets.UTF_16LE,
      StandardCharsets.UTF_16BE,
      StandardCharsets.UTF_16,
      StandardCharsets.UTF_8
    ).foreach { charset =>
      test(s"decode ${charset.toString}") {
        (1 to 6).foreach { n =>
          val in = (1 to 50).map(_ => "⁂ boo foo woo ⁋").mkString("")
          val encoded = in.getBytes(charset)
          val stream = Stream
            .chunk(Chunk.array(encoded))
            .chunkLimit(n)
            .unchunks
            .covary[Fallible]

          val out = stream
            .through(text.decodeWithCharset(charset))
            .compile
            .toList
            .fold(throw _, identity)
            .mkString("")

          assertEquals(out, in)
        }
      }
    }

    test("flushes decoder") {
      // Obscure example found through property test in http4s.  Most
      // charsets don't require flushing.  Search "implFlush" in JDK
      // source for more.
      if (isJVM) { // Charset not supported by Scala.js
        val cs = Charset.forName("x-ISCII91")
        val s = Stream(0xdc.toByte)
          .covary[Fallible]
          .through(decodeWithCharset(cs))
          .compile
          .string
        assertEquals(s, Right("\u0940"))
      }
    }
  }
}
