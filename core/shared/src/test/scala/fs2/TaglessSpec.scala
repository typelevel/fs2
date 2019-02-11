package fs2.tagless

import fs2.{Chunk, ChunkGen}

import cats.effect.IO

import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class TaglessSpec extends FreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ChunkGen {

  "Stream" - {
    "chunk" in { forAll { (c: Chunk[Int]) => Stream.chunk(c).toChunk shouldBe c } }
    "eval" in { Stream.eval(IO(23)).compile.toList.unsafeRunSync shouldBe List(23) }
  }
}
