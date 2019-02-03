package fs2.tagless

import fs2.Chunk

import cats.effect.IO

import org.scalacheck._
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class TaglessSpec extends FreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  implicit def arbChunk[A](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] = Arbitrary(
    Gen.frequency(
      10 -> Gen.listOf(A.arbitrary).map(as => Chunk.vector(as.toVector)),
      10 -> Gen.listOf(A.arbitrary).map(Chunk.seq),
      5 -> A.arbitrary.map(a => Chunk.singleton(a)),
      1 -> Chunk.empty[A]
    )
  )

  implicit def cogenChunk[A: Cogen]: Cogen[Chunk[A]] =
    Cogen[List[A]].contramap(_.toList)


  "Stream" - {
    "chunk" in { forAll { (c: Chunk[Int]) => Stream.chunk(c).toChunk shouldBe c } }
    "eval" in { Stream.eval(IO(23)).compile.toList.unsafeRunSync shouldBe List(23) }
  }
}