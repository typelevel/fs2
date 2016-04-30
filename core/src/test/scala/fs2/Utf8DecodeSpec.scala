package fs2

import org.scalacheck._
import fs2.text._

class Utf8DecodeSpec extends Fs2Spec {
  "text" - {
    "utf8Decoder" - {

      def utf8Bytes(s: String): Chunk[Byte] = Chunk.bytes(s.getBytes("UTF-8"))
      def utf8String(bs: Chunk[Byte]): String = new String(bs.toArray, "UTF-8")

      def checkChar(c: Char) = (1 to 6).foreach { n =>
        Stream.chunk(utf8Bytes(c.toString)).pure.chunkLimit(n).through(utf8Decode).toList shouldBe List(c.toString)
      }

      def checkBytes(is: Int*) = (1 to 6).foreach { n =>
        val bytes = Chunk.bytes(is.map(_.toByte).toArray)
        Stream.chunk(bytes).pure.chunkLimit(n).through(utf8Decode).toList shouldBe List(utf8String(bytes))
      }

      def checkBytes2(is: Int*) = {
        val bytes = Chunk.bytes(is.map(_.toByte).toArray)
        Stream.pure(bytes).through(utf8Decode).toList.mkString shouldBe utf8String(bytes)
      }

      "all chars" in forAll { (c: Char) => checkChar(c) }

      "1 byte char" in checkBytes(0x24) // $
      "2 byte char" in checkBytes(0xC2, 0xA2) // ¢
      "3 byte char" in checkBytes(0xE2, 0x82, 0xAC) // €
      "4 byte char" in checkBytes(0xF0, 0xA4, 0xAD, 0xA2)

      "incomplete 2 byte char" in checkBytes(0xC2)
      "incomplete 3 byte char" in checkBytes(0xE2, 0x82)
      "incomplete 4 byte char" in checkBytes(0xF0, 0xA4, 0xAD)

      "preserve complete inputs" in forAll { (l: List[String]) =>
        Stream.pure(l: _*).map(utf8Bytes).through(utf8Decode).toList shouldBe l
      }

      "utf8Encode |> utf8Decode = id" in forAll { (s: String) =>
        Stream.pure(s).through(utf8Encode).through(utf8Decode).toList shouldBe List(s)
      }

      "1 byte sequences" in forAll { (s: String) =>
        Stream.chunk(utf8Bytes(s)).pure.chunkLimit(1).through(utf8Decode).toList shouldBe s.grouped(1).toList
      }

      "n byte sequences" in forAll { (s: String) =>
        val n = Gen.choose(1,9).sample.getOrElse(1)
        Stream.chunk(utf8Bytes(s)).pure.chunkLimit(1).through(utf8Decode).toList.mkString shouldBe s
      }

      // The next tests were taken from:
      // https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-test.txt

      // 2.1 First possible sequence of a certain length
      "2.1" - {
        "2.1.1" in checkBytes(0x00)
        "2.1.2" in checkBytes(0xc2, 0x80)
        "2.1.3" in checkBytes(0xe0, 0xa0, 0x80)
        "2.1.4" in checkBytes(0xf0, 0x90, 0x80, 0x80)
        "2.1.5" in checkBytes2(0xf8, 0x88, 0x80, 0x80, 0x80)
        "2.1.6" in checkBytes2(0xfc, 0x84, 0x80, 0x80, 0x80, 0x80)
      }

      // 2.2 Last possible sequence of a certain length
      "2.2" - {
        "2.2.1" in checkBytes(0x7f)
        "2.2.2" in checkBytes(0xdf, 0xbf)
        "2.2.3" in checkBytes(0xef, 0xbf, 0xbf)
        "2.2.4" in checkBytes(0xf7, 0xbf, 0xbf, 0xbf)
        "2.2.5" in checkBytes2(0xfb, 0xbf, 0xbf, 0xbf, 0xbf)
        "2.2.6" in checkBytes2(0xfd, 0xbf, 0xbf, 0xbf, 0xbf, 0xbf)
      }

      // 2.3 Other boundary conditions
      "2.3" - {
        "2.3.1" in checkBytes(0xed, 0x9f, 0xbf)
        "2.3.2" in checkBytes(0xee, 0x80, 0x80)
        "2.3.3" in checkBytes(0xef, 0xbf, 0xbd)
        "2.3.4" in checkBytes(0xf4, 0x8f, 0xbf, 0xbf)
        "2.3.5" in checkBytes(0xf4, 0x90, 0x80, 0x80)
      }

      // 3.1 Unexpected continuation bytes
      "3.1" - {
        "3.1.1" in checkBytes(0x80)
        "3.1.2" in checkBytes(0xbf)
      }

      // 3.5 Impossible bytes
      "3.5" - {
        "3.5.1" in checkBytes(0xfe)
        "3.5.2" in checkBytes(0xff)
        "3.5.3" in checkBytes2(0xfe, 0xfe, 0xff, 0xff)
      }

      // 4.1 Examples of an overlong ASCII character
      "4.1" - {
        "4.1.1" in checkBytes(0xc0, 0xaf)
        "4.1.2" in checkBytes(0xe0, 0x80, 0xaf)
        "4.1.3" in checkBytes(0xf0, 0x80, 0x80, 0xaf)
        "4.1.4" in checkBytes2(0xf8, 0x80, 0x80, 0x80, 0xaf)
        "4.1.5" in checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0xaf)
      }

      // 4.2 Maximum overlong sequences
      "4.2" - {
        "4.2.1" in checkBytes(0xc1, 0xbf)
        "4.2.2" in checkBytes(0xe0, 0x9f, 0xbf)
        "4.2.3" in checkBytes(0xf0, 0x8f, 0xbf, 0xbf)
        "4.2.4" in checkBytes2(0xf8, 0x87, 0xbf, 0xbf, 0xbf)
        "4.2.5" in checkBytes2(0xfc, 0x83, 0xbf, 0xbf, 0xbf, 0xbf)
      }

      // 4.3 Overlong representation of the NUL character
      "4.3" - {
        "4.3.1" in checkBytes(0xc0, 0x80)
        "4.3.2" in checkBytes(0xe0, 0x80, 0x80)
        "4.3.3" in checkBytes(0xf0, 0x80, 0x80, 0x80)
        "4.3.4" in checkBytes2(0xf8, 0x80, 0x80, 0x80, 0x80)
        "4.3.5" in checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0x80)
      }

      // 5.1 Single UTF-16 surrogates
      "5.1" - {
        "5.1.1" in checkBytes(0xed, 0xa0, 0x80)
        "5.1.2" in checkBytes(0xed, 0xad, 0xbf)
        "5.1.3" in checkBytes(0xed, 0xae, 0x80)
        "5.1.4" in checkBytes(0xed, 0xaf, 0xbf)
        "5.1.5" in checkBytes(0xed, 0xb0, 0x80)
        "5.1.6" in checkBytes(0xed, 0xbe, 0x80)
        "5.1.7" in checkBytes(0xed, 0xbf, 0xbf)
      }

      // 5.2 Paired UTF-16 surrogates
      "5.2" - {
        "5.2.1" in checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xb0, 0x80)
        "5.2.2" in checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xbf, 0xbf)
        "5.2.3" in checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xb0, 0x80)
        "5.2.4" in checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xbf, 0xbf)
        "5.2.5" in checkBytes2(0xed, 0xae, 0x80, 0xed, 0xb0, 0x80)
        "5.2.6" in checkBytes2(0xed, 0xae, 0x80, 0xed, 0xbf, 0xbf)
        "5.2.7" in checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xb0, 0x80)
        "5.2.8" in checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xbf, 0xbf)
      }

      // 5.3 Other illegal code positions
      "5.3" - {
        "5.3.1" in checkBytes(0xef, 0xbf, 0xbe)
        "5.3.2" in checkBytes(0xef, 0xbf, 0xbf)
      }
    }
  }
}
