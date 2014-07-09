package scalaz.stream

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.scalacheck._
import org.scalacheck.Prop._
import scodec.bits.ByteVector

import Process._
import hash._

import TestInstances._
import scala.util.Random
import scalaz._
import scalaz.syntax.id._

object HashSpec extends Properties("hash") {
  def digest(algo: String, str: String): List[Byte] =
    MessageDigest.getInstance(algo).digest(str.getBytes).toList

  def digest(algo: String, content: Array[Byte]): List[Byte] =
    MessageDigest.getInstance(algo).digest(content).toList

  def checkDigest(h: Process1[ByteVector,ByteVector], algo: String, str: String): Boolean = {
    val n = Gen.choose(1, str.length).sample.getOrElse(1)
    val p =
      if (str.isEmpty) emit(ByteVector.view(str.getBytes))
      else emitSeq(ByteVector.view(str.getBytes).grouped(n).toSeq)

    p.pipe(h).map(_.toArray.toList).toList == List(digest(algo, str))
  }

  property("all") = forAll { (s: String) =>
    ("md2"    |: checkDigest(md2,    "MD2",     s)) &&
    ("md5"    |: checkDigest(md5,    "MD5",     s)) &&
    ("sha1"   |: checkDigest(sha1,   "SHA-1",   s)) &&
    ("sha256" |: checkDigest(sha256, "SHA-256", s)) &&
    ("sha384" |: checkDigest(sha384, "SHA-384", s)) &&
    ("sha512" |: checkDigest(sha512, "SHA-512", s))
  }

  property("empty input") = secure {
    Process[ByteVector]().pipe(md2).toList.isEmpty
  }

  property("zero or one output") = forAll { (lb: List[ByteVector]) =>
    emitSeq(lb).pipe(md2).toList.length <= 1
  }

  property("thread-safety") = secure {
    val proc = range(1,100)
      .map(i => ByteVector.view(i.toString.getBytes))
      .pipe(sha512).map(_.toSeq)
    val vec = Vector.fill(100)(proc).par
    val res = proc.runLast.run

    vec.map(_.runLast.run).forall(_ == res)
  }

  property("hashes over all of stream") = forAll { (b: LargeContent) =>
      ("md2"    |: checkDigest(md2,    "MD2",     b)) &&
      ("md5"    |: checkDigest(md5,    "MD5",     b)) &&
      ("sha1"   |: checkDigest(sha1,   "SHA-1",   b)) &&
      ("sha256" |: checkDigest(sha256, "SHA-256", b)) &&
      ("sha384" |: checkDigest(sha384, "SHA-384", b)) &&
      ("sha512" |: checkDigest(sha512, "SHA-512", b))
  }

  def checkDigest(h: Process1[ByteVector, ByteVector], algo: String, content: Array[Byte]): Boolean = {
    val n = Gen.choose(1, content.length).sample.getOrElse(1)
    val p =
      Process.constant(n)
        .through(io.chunkR(new ByteArrayInputStream(content)))

    p.pipe(h).map(_.toArray.toList).runLast.run == Some(digest(algo, content))
  }

  sealed trait Large
  type LargeContent = Array[Byte] @@ Large
  implicit def arbitraryLargeContent: Arbitrary[LargeContent] = Arbitrary(
    Gen.chooseNum(1, 1000000).map(size => new Array[Byte](size) <| ((new Random).nextBytes)).map(Tag.apply)
  )
}
