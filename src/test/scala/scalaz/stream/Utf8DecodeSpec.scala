package scalaz.stream

import org.scalacheck._
import Prop._
import scalaz.std.list._
import scalaz.std.string._
import scalaz.syntax.equal._
import scodec.bits.ByteVector

import Process._
import text._

class Utf8DecodeSpec extends Properties("text.utf8Decode") {

  def utf8Bytes(a: Array[Int]): ByteVector = ByteVector(a.map(_.toByte))
  def utf8Bytes(c: Char): ByteVector = utf8Bytes(c.toString)
  def utf8Bytes(s: String): ByteVector = ByteVector(s.getBytes("UTF-8"))
  def utf8String(bs: ByteVector): String = new String(bs.toArray, "UTF-8")

  def checkChar(c: Char): Boolean = (1 to 6).forall { n =>
    emitAll(utf8Bytes(c).grouped(n).toSeq).pipe(utf8Decode).toList === List(c.toString)
  }

  def checkBytes(is: Int*): Boolean = (1 to 6).forall { n =>
    val bytes = utf8Bytes(is.toArray)
    emitAll(bytes.grouped(n).toSeq).pipe(utf8Decode).toList === List(utf8String(bytes))
  }

  def checkBytes2(is: Int*): Boolean = {
    val bytes = utf8Bytes(is.toArray)
    emit(bytes).pipe(utf8Decode).toList.mkString === utf8String(bytes)
  }

  property("all chars") = forAll { (c: Char) => checkChar(c) }

  property("1 byte char") = checkBytes(0x24) // $
  property("2 byte char") = checkBytes(0xC2, 0xA2) // ¢
  property("3 byte char") = checkBytes(0xE2, 0x82, 0xAC) // €
  property("4 byte char") = checkBytes(0xF0, 0xA4, 0xAD, 0xA2)

  property("incomplete 2 byte char") = checkBytes(0xC2)
  property("incomplete 3 byte char") = checkBytes(0xE2, 0x82)
  property("incomplete 4 byte char") = checkBytes(0xF0, 0xA4, 0xAD)

  property("preserve complete inputs") = forAll { (l: List[String]) =>
    emitAll(l).map(utf8Bytes).pipe(utf8Decode).toList === l
  }

  property("utf8Encode |> utf8Decode = id") = forAll { (s: String) =>
    emit(s).pipe(utf8Encode).pipe(utf8Decode).toList === List(s)
  }

  property("1 byte sequences") = forAll { (s: String) =>
    emitAll(utf8Bytes(s).grouped(1).toSeq).pipe(utf8Decode).toList === s.grouped(1).toList
  }

  property("n byte sequences") = forAll { (s: String) =>
    val n = Gen.choose(1,9).sample.getOrElse(1)
    emitAll(utf8Bytes(s).grouped(n).toSeq).pipe(utf8Decode).toList.mkString === s
  }

  // The next tests were taken from:
  // https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-test.txt

  // 2.1 First possible sequence of a certain length
  property("2.1") = protect {
    ("2.1.1" |: checkBytes(0x00)) &&
    ("2.1.2" |: checkBytes(0xc2, 0x80)) &&
    ("2.1.3" |: checkBytes(0xe0, 0xa0, 0x80)) &&
    ("2.1.4" |: checkBytes(0xf0, 0x90, 0x80, 0x80)) &&
    ("2.1.5" |: checkBytes2(0xf8, 0x88, 0x80, 0x80, 0x80)) &&
    ("2.1.6" |: checkBytes2(0xfc, 0x84, 0x80, 0x80, 0x80, 0x80))
  }

  // 2.2 Last possible sequence of a certain length
  property("2.2") = protect {
    ("2.2.1" |: checkBytes(0x7f)) &&
    ("2.2.2" |: checkBytes(0xdf, 0xbf)) &&
    ("2.2.3" |: checkBytes(0xef, 0xbf, 0xbf)) &&
    ("2.2.4" |: checkBytes(0xf7, 0xbf, 0xbf, 0xbf)) &&
    ("2.2.5" |: checkBytes2(0xfb, 0xbf, 0xbf, 0xbf, 0xbf)) &&
    ("2.2.6" |: checkBytes2(0xfd, 0xbf, 0xbf, 0xbf, 0xbf, 0xbf))
  }

  // 2.3 Other boundary conditions
  property("2.3") = protect {
    ("2.3.1" |: checkBytes(0xed, 0x9f, 0xbf)) &&
    ("2.3.2" |: checkBytes(0xee, 0x80, 0x80)) &&
    ("2.3.3" |: checkBytes(0xef, 0xbf, 0xbd)) &&
    ("2.3.4" |: checkBytes(0xf4, 0x8f, 0xbf, 0xbf)) &&
    ("2.3.5" |: checkBytes(0xf4, 0x90, 0x80, 0x80))
  }

  // 3.1 Unexpected continuation bytes
  property("3.1") = protect {
    ("3.1.1" |: checkBytes(0x80)) &&
    ("3.1.2" |: checkBytes(0xbf))
  }

  // 3.5 Impossible bytes
  property("3.5") = protect {
    ("3.5.1" |: checkBytes(0xfe)) &&
    ("3.5.2" |: checkBytes(0xff)) &&
    ("3.5.3" |: checkBytes2(0xfe, 0xfe, 0xff, 0xff))
  }

  // 4.1 Examples of an overlong ASCII character
  property("4.1") = protect {
    ("4.1.1" |: checkBytes(0xc0, 0xaf)) &&
    ("4.1.2" |: checkBytes(0xe0, 0x80, 0xaf)) &&
    ("4.1.3" |: checkBytes(0xf0, 0x80, 0x80, 0xaf)) &&
    ("4.1.4" |: checkBytes2(0xf8, 0x80, 0x80, 0x80, 0xaf)) &&
    ("4.1.5" |: checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0xaf))
  }

  // 4.2 Maximum overlong sequences
  property("4.2") = protect {
    ("4.2.1" |: checkBytes(0xc1, 0xbf)) &&
    ("4.2.2" |: checkBytes(0xe0, 0x9f, 0xbf)) &&
    ("4.2.3" |: checkBytes(0xf0, 0x8f, 0xbf, 0xbf)) &&
    ("4.2.4" |: checkBytes2(0xf8, 0x87, 0xbf, 0xbf, 0xbf)) &&
    ("4.2.5" |: checkBytes2(0xfc, 0x83, 0xbf, 0xbf, 0xbf, 0xbf))
  }

  // 4.3 Overlong representation of the NUL character
  property("4.3") = protect {
    ("4.3.1" |: checkBytes(0xc0, 0x80)) &&
    ("4.3.2" |: checkBytes(0xe0, 0x80, 0x80)) &&
    ("4.3.3" |: checkBytes(0xf0, 0x80, 0x80, 0x80)) &&
    ("4.3.4" |: checkBytes2(0xf8, 0x80, 0x80, 0x80, 0x80)) &&
    ("4.3.5" |: checkBytes2(0xfc, 0x80, 0x80, 0x80, 0x80, 0x80))
  }

  // 5.1 Single UTF-16 surrogates
  property("5.1") = protect {
    ("5.1.1" |: checkBytes(0xed, 0xa0, 0x80)) &&
    ("5.1.2" |: checkBytes(0xed, 0xad, 0xbf)) &&
    ("5.1.3" |: checkBytes(0xed, 0xae, 0x80)) &&
    ("5.1.4" |: checkBytes(0xed, 0xaf, 0xbf)) &&
    ("5.1.5" |: checkBytes(0xed, 0xb0, 0x80)) &&
    ("5.1.6" |: checkBytes(0xed, 0xbe, 0x80)) &&
    ("5.1.7" |: checkBytes(0xed, 0xbf, 0xbf))
  }

  // 5.2 Paired UTF-16 surrogates
  property("5.2") = protect {
    ("5.2.1" |: checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xb0, 0x80)) &&
    ("5.2.2" |: checkBytes2(0xed, 0xa0, 0x80, 0xed, 0xbf, 0xbf)) &&
    ("5.2.3" |: checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xb0, 0x80)) &&
    ("5.2.4" |: checkBytes2(0xed, 0xad, 0xbf, 0xed, 0xbf, 0xbf)) &&
    ("5.2.5" |: checkBytes2(0xed, 0xae, 0x80, 0xed, 0xb0, 0x80)) &&
    ("5.2.6" |: checkBytes2(0xed, 0xae, 0x80, 0xed, 0xbf, 0xbf)) &&
    ("5.2.7" |: checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xb0, 0x80)) &&
    ("5.2.8" |: checkBytes2(0xed, 0xaf, 0xbf, 0xed, 0xbf, 0xbf))
  }

  // 5.3 Other illegal code positions
  property("5.3") = protect {
    ("5.3.1" |: checkBytes(0xef, 0xbf, 0xbe)) &&
    ("5.3.2" |: checkBytes(0xef, 0xbf, 0xbf))
  }
}

