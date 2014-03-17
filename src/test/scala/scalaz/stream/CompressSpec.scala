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

  property("gzip and gunzip") = secure {
    // $ echo -n Hello | gzip -f | xxd -l 16
    // 0000000: 1f8b 0800 e22d 2753 0003 f348 cdc9 c907  .....-'S...H....

    val hello = getBytes("Hello")
    val helloGz = ByteVector.fromValidHex("1f8b 0800 e22d 2753 0003 f348 cdc9 c907")

    emit(hello).pipe(gzip).toList == List(helloGz) &&
    emit(helloGz).pipe(gunzip).toList == List(hello)

    true
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

  property("empty input") = secure {
    Process[ByteVector]().pipe(deflate()).toList == List() &&
    Process[ByteVector]().pipe(inflate()).toList == List()
  }

  property("single byte inputs") = forAll { (s: String) =>
    val input = getBytes(s).grouped(1).toList
    val deflated = emitSeq(input).pipe(deflate()).toList.flatMap(_.grouped(1))
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }
}
