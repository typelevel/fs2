package scalaz.stream

import org.scalacheck._
import Prop._
import scodec.bits.ByteVector

import Process._
import compress._

object CompressSpec extends Properties("compress") {
  def getBytes(s: String): ByteVector =
    ByteVector.view(s.getBytes)

  def foldBytes(bytes: List[ByteVector]): ByteVector =
    bytes.fold(ByteVector.empty)(_ ++ _)

  property("deflate.empty input") = secure {
    Process[ByteVector]().pipe(deflate()).toList.isEmpty
  }

  property("inflate.empty input") = secure {
    Process[ByteVector]().pipe(inflate()).toList.isEmpty
  }

  property("deflate |> inflate ~= id") = forAll { (ls: List[String]) =>
    val input = ls.map(getBytes)
    val inflated = emitSeq(input).pipe(deflate()).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate") = forAll { (ls: List[String]) =>
    val input = ls.map(getBytes)
    val deflated = emitSeq(input).pipe(deflate()).toList
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate with small buffers") = forAll { (ls: List[String]) =>
    val input = ls.map(getBytes)
    val deflated = emitSeq(input).pipe(deflate(0, false, 32)).toList
    val inflated = emitSeq(deflated).pipe(inflate(false, 32)).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("(de|in)flate with single byte inputs") = forAll { (s: String) =>
    val input = getBytes(s).grouped(1).toList
    val deflated = emitSeq(input).pipe(deflate()).toList.flatMap(_.grouped(1))
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }
}
