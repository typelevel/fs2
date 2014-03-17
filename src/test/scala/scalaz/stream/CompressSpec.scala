package scalaz.stream

import org.scalacheck._
import Prop._
import scodec.bits.ByteVector

import Process._
import compress._

object CompressSpec extends Properties("compress") {
  def foldBytes(bytes: List[ByteVector]): ByteVector =
    bytes.fold(ByteVector.empty)(_ ++ _)

  property("deflate and inflate") = forAll { (ls: List[String]) =>
    val input = ls.map(s => ByteVector.view(s.getBytes))
    val deflated = emitSeq(input).pipe(deflate()).toList
    val inflated = emitSeq(deflated).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("deflate |> inflate ~= id") = forAll { (ls: List[String]) =>
    val input = ls.map(s => ByteVector.view(s.getBytes))
    val inflated = emitSeq(input).pipe(deflate()).pipe(inflate()).toList

    foldBytes(input) == foldBytes(inflated)
  }

  property("empty input") = secure {
    Process[ByteVector]().pipe(deflate()).toList == List() &&
    Process[ByteVector]().pipe(inflate()).toList == List()
  }
}
