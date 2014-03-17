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

  val hello = getBytes("Hello")
  val helloGz =
    ByteVector.fromValidHex("1f8b 0800 0000 0000 02ff f248 cdc9 c907 0000 00ff ff")

  property("gzip.empty input") = secure {
    Process[ByteVector]().pipe(gzip).toList == List()
  }

  property("gunzip.empty input") = secure {
    Process[ByteVector]().pipe(gunzip).toList == List()
  }

  property("gzip.static input") = secure {
    foldBytes(emit(hello).pipe(gzip).toList) == helloGz
  }

  property("gunzip.static input") = secure {
    foldBytes(emit(helloGz).pipe(gunzip).toList) == hello
  }

  property("deflate.empty input") = secure {
    Process[ByteVector]().pipe(deflate()).toList == List()
  }

  property("inflate.empty input") = secure {
    Process[ByteVector]().pipe(inflate()).toList == List()
  }

  property("deflate and inflate") = forAll { (ls: List[String]) =>
    val input = ls.map(getBytes)
    val deflated = emitSeq(input).pipe(deflate()).toList
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("deflate |> inflate ~= id") = forAll { (ls: List[String]) =>
    val input = ls.map(getBytes)
    val inflated = emitSeq(input).pipe(deflate()).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("single byte inputs") = forAll { (s: String) =>
    val input = getBytes(s).grouped(1).toList
    val deflated = emitSeq(input).pipe(deflate()).toList.flatMap(_.grouped(1))
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }
}
