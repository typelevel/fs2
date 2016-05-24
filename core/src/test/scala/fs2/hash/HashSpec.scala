package fs2
package hash

import java.security.MessageDigest
import org.scalacheck.Gen

import fs2.util.Task

class HashSpec extends Fs2Spec {
  def digest(algo: String, str: String): List[Byte] =
    MessageDigest.getInstance(algo).digest(str.getBytes).toList

  def checkDigest[A](h: Pipe[Pure,Byte,Byte], algo: String, str: String) = {
    val n = Gen.choose(1, str.length).sample.getOrElse(1)
    val s =
      if (str.isEmpty) Stream.empty
      else str.getBytes.grouped(n).foldLeft(Stream.empty[Pure,Byte])((acc, c) => acc ++ Stream.chunk(Chunk.bytes(c)))

    s.through(h).toList shouldBe digest(algo, str)
  }

  "digests" - {
    "md2" in forAll { (s: String) => checkDigest(md2, "MD2", s) }
    "md5" in forAll { (s: String) => checkDigest(md5, "MD5", s) }
    "sha1" in forAll { (s: String) => checkDigest(sha1, "SHA-1", s) }
    "sha256" in forAll { (s: String) => checkDigest(sha256, "SHA-256", s) }
    "sha384" in forAll { (s: String) => checkDigest(sha384, "SHA-384", s) }
    "sha512" in forAll { (s: String) => checkDigest(sha512, "SHA-512", s) }
  }

  "empty input" in {
    Stream.empty[Pure,Byte].through(sha1).toList should have size(20)
  }

  "zero or one output" in forAll { (lb: List[Array[Byte]]) =>
    lb.foldLeft(Stream.empty[Pure,Byte])((acc, b) => acc ++ Stream.chunk(Chunk.bytes(b))).through(sha1).toList should have size(20)
  }

  "thread-safety" in {
    val s = Stream.range[Task](1,100)
      .flatMap(i => Stream.chunk(Chunk.bytes(i.toString.getBytes)))
      .through(sha512)
    val vec = Vector.fill(100)(s).par
    val res = s.runLog.unsafeRun
    vec.map(_.runLog.unsafeRun) shouldBe Vector.fill(100)(res)
  }
}

