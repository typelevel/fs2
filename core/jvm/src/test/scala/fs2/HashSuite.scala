package fs2

import cats.effect.IO
import cats.implicits._
import java.security.MessageDigest
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import hash._

class HashSuite extends Fs2Suite {
  def digest(algo: String, str: String): List[Byte] =
    MessageDigest.getInstance(algo).digest(str.getBytes).toList

  def checkDigest[A](h: Pipe[Pure, Byte, Byte], algo: String, str: String) = {
    val n =
      if (str.length > 0) Gen.choose(1, str.length).sample.getOrElse(1) else 1
    val s =
      if (str.isEmpty) Stream.empty
      else
        str.getBytes
          .grouped(n)
          .foldLeft(Stream.empty.covaryOutput[Byte])((acc, c) =>
            acc ++ Stream.chunk(Chunk.bytes(c))
          )

    assert(s.through(h).toList == digest(algo, str))
  }

  group("digests") {
    test("md2")(forAll((s: String) => checkDigest(md2, "MD2", s)))
    test("md5")(forAll((s: String) => checkDigest(md5, "MD5", s)))
    test("sha1")(forAll((s: String) => checkDigest(sha1, "SHA-1", s)))
    test("sha256")(forAll((s: String) => checkDigest(sha256, "SHA-256", s)))
    test("sha384")(forAll((s: String) => checkDigest(sha384, "SHA-384", s)))
    test("sha512")(forAll((s: String) => checkDigest(sha512, "SHA-512", s)))
  }

  test("empty input") {
    assert(Stream.empty.through(sha1).toList.size == 20)
  }

  test("zero or one output") {
    forAll { (lb: List[Array[Byte]]) =>
      val size = lb
        .foldLeft(Stream.empty.covaryOutput[Byte])((acc, b) => acc ++ Stream.chunk(Chunk.bytes(b)))
        .through(sha1)
        .toList
        .size
      assert(size == 20)
    }
  }

  test("thread-safety") {
    val s = Stream
      .range(1, 100)
      .covary[IO]
      .flatMap(i => Stream.chunk(Chunk.bytes(i.toString.getBytes)))
      .through(sha512)
    (for {
      once <- s.compile.toVector
      oneHundred <- Vector.fill(100)(s.compile.toVector).parSequence
    } yield (once, oneHundred)).map {
      case (once, oneHundred) => assert(oneHundred == Vector.fill(100)(once))
    }
  }
}
