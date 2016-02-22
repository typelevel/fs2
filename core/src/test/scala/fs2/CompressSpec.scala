package fs2

import fs2.Stream._
import fs2.TestUtil._
import org.scalacheck.Prop._
import org.scalacheck.Properties

import compress._

object CompressSpec extends Properties("compress") {

  def getBytes(s: String): Array[Byte] =
    s.getBytes

  property("deflate.empty input") = protect {
    Stream.empty[Pure, Byte].pipe(deflate()).toVector.isEmpty
  }

  property("inflate.empty input") = protect {
    Stream.empty[Pure, Byte].pipe(inflate()).toVector.isEmpty
  }

  property("deflate |> inflate ~= id") = forAll { (s: PureStream[Byte]) =>
    s.get ==? s.get.pipe(compress.deflate()).pipe(compress.inflate()).toVector
  }

  property("deflate.compresses input") = protect {
    val uncompressed = getBytes(
      """"
        |"A type system is a tractable syntactic method for proving the absence
        |of certain program behaviors by classifying phrases according to the
        |kinds of values they compute."
        |-- Pierce, Benjamin C. (2002). Types and Programming Languages""")
    val compressed = Stream.chunk(Chunk.bytes(uncompressed)).pipe(deflate(9)).toVector

    compressed.length < uncompressed.length
  }
}
