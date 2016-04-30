package fs2

import fs2.Stream._

import compress._

class CompressSpec extends Fs2Spec {

  def getBytes(s: String): Array[Byte] =
    s.getBytes

  "Compress" - {

    "deflate.empty input" in {
      assert(Stream.empty[Pure, Byte].through(deflate()).toVector.isEmpty)
    }

    "inflate.empty input" in {
      assert(Stream.empty[Pure, Byte].through(inflate()).toVector.isEmpty)
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
      val compressed = Stream.chunk(Chunk.bytes(uncompressed)).throughp(deflate(9)).toVector

      compressed.length should be < uncompressed.length
    }
  }
}
