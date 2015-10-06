package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.-\/
import scodec.bits.ByteVector

import Process._
import compress._

import TestInstances._

class CompressSpec extends Properties("compress") {
  def getBytes(s: String): ByteVector =
    ByteVector.view(s.getBytes)

  def foldBytes(bytes: List[ByteVector]): ByteVector =
    bytes.fold(ByteVector.empty)(_ ++ _)

  property("deflate.empty input") = protect {
    Process[ByteVector]().pipe(deflate()).toList.isEmpty
  }

  property("inflate.empty input") = protect {
    Process[ByteVector]().pipe(inflate()).toList.isEmpty
  }

  property("deflate |> inflate ~= id") = forAll { (input: List[ByteVector]) =>
    val inflated = emitAll(input).pipe(deflate()).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate") = forAll { (input: List[ByteVector]) =>
    val deflated = emitAll(input).pipe(deflate()).toList
    val inflated = emitAll(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate with small buffers") = forAll { (input: List[ByteVector]) =>
    val deflated = emitAll(input).pipe(deflate(0, false, 32)).toList
    val inflated = emitAll(deflated).pipe(inflate(false, 32)).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate with single byte inputs") = forAll { (bs: ByteVector) =>
    val input = bs.grouped(1).toList
    val deflated = emitAll(input).pipe(deflate()).toList.flatMap(_.grouped(1))
    val inflated = emitAll(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("deflate.compresses input") = protect {
    val uncompressed = getBytes(
      """"
        |"A type system is a tractable syntactic method for proving the absence
        |of certain program behaviors by classifying phrases according to the
        |kinds of values they compute."
        |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
    val compressed = foldBytes(emit(uncompressed).pipe(deflate(9)).toList)

    compressed.length < uncompressed.length
  }

  property("inflate.uncompressed input") = protect {
    emit(getBytes("Hello")).pipe(inflate()).attempt().toList match {
      case List(-\/(ex: java.util.zip.DataFormatException)) => true
      case _ => false
    }
  }
}

