package fs2

import org.scalacheck.{Gen, Arbitrary}

import ChunkProps._

class ChunkSpec extends Fs2Spec {

  "Chunk" - {

    "chunk-formation (1)" in {
      Chunk.empty.toList shouldBe List()
      Chunk.singleton(23).toList shouldBe List(23)
    }

    "chunk-formation (2)" in forAll { (c: Vector[Int]) =>
      Chunk.seq(c).toVector shouldBe c
      Chunk.seq(c).toList shouldBe c.toList
      Chunk.indexedSeq(c).toVector shouldBe c
      Chunk.indexedSeq(c).toList shouldBe c.toList
      Chunk.seq(c).iterator.toList shouldBe c.iterator.toList
      Chunk.indexedSeq(c).iterator.toList shouldBe c.iterator.toList
    }


    implicit val arbBooleanChunk: Arbitrary[Chunk[Boolean]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Boolean](n, Arbitrary.arbBool.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield Chunk.booleans(values, offset, sz)
    }

    implicit val arbByteChunk: Arbitrary[Chunk[Byte]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield Chunk.bytes(values, offset, sz)
    }

    implicit val arbDoubleChunk: Arbitrary[Chunk[Double]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Double](n, Arbitrary.arbDouble.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield Chunk.doubles(values, offset, sz)
    }

    implicit val arbLongChunk: Arbitrary[Chunk[Long]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Long](n, Arbitrary.arbLong.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield Chunk.longs(values, offset, sz)
    }

    "size.boolean" in propSize[Boolean, Chunk[Boolean]]
    "size.byte" in propSize[Byte, Chunk[Byte]]
    "size.double" in propSize[Double, Chunk[Double]]
    "size.long" in propSize[Long, Chunk[Long]]
    "size.unspecialized" in propSize[Int, Chunk[Int]]

    "take.boolean" in propTake[Boolean, Chunk[Boolean]]
    "take.byte" in propTake[Byte, Chunk[Byte]]
    "take.double" in propTake[Double, Chunk[Double]]
    "take.long" in propTake[Long, Chunk[Long]]
    "take.unspecialized" in propTake[Int, Chunk[Int]]

    "drop.boolean" in propDrop[Boolean, Chunk[Boolean]]
    "drop.byte" in propDrop[Byte, Chunk[Byte]]
    "drop.double" in propDrop[Double, Chunk[Double]]
    "drop.long" in propDrop[Long, Chunk[Long]]
    "drop.unspecialized" in propDrop[Int, Chunk[Int]]

    "uncons.boolean" in propUncons[Boolean, Chunk[Boolean]]
    "uncons.byte" in propUncons[Byte, Chunk[Byte]]
    "uncons.double" in propUncons[Double, Chunk[Double]]
    "uncons.long" in propUncons[Long, Chunk[Long]]
    "uncons.unspecialized" in propUncons[Int, Chunk[Int]]

    "isempty.boolean" in propIsEmpty[Boolean, Chunk[Boolean]]
    "isempty.byte" in propIsEmpty[Byte, Chunk[Byte]]
    "isempty.double" in propIsEmpty[Double, Chunk[Double]]
    "isempty.long" in propIsEmpty[Long, Chunk[Long]]
    "isempty.unspecialized" in propIsEmpty[Int, Chunk[Int]]

    "filter.boolean" in propFilter[Boolean, Chunk[Boolean]]
    "filter.byte" in propFilter[Byte, Chunk[Byte]]
    "filter.double" in propFilter[Double, Chunk[Double]]
    "filter.long" in propFilter[Long, Chunk[Long]]
    "filter.unspecialized" in propFilter[Int, Chunk[Int]]

    "foldleft.boolean" in propFoldLeft[Boolean, Chunk[Boolean]]
    "foldleft.byte" in propFoldLeft[Byte, Chunk[Byte]]
    "foldleft.double" in propFoldLeft[Double, Chunk[Double]]
    "foldleft.long" in propFoldLeft[Long, Chunk[Long]]
    "foldleft.unspecialized" in propFoldLeft[Int, Chunk[Int]]

    "foldright.boolean" in propFoldRight[Boolean, Chunk[Boolean]]
    "foldright.byte" in propFoldRight[Byte, Chunk[Byte]]
    "foldright.double" in propFoldRight[Double, Chunk[Double]]
    "foldright.long" in propFoldRight[Long, Chunk[Long]]
    "foldright.unspecialized" in propFoldRight[Int, Chunk[Int]]

    "toarray.boolean" in propToArray[Boolean, Chunk[Boolean]]
    "toarray.byte" in propToArray[Byte, Chunk[Byte]]
    "toarray.double" in propToArray[Double, Chunk[Double]]
    "toarray.long" in propToArray[Long, Chunk[Long]]
    "toarray.unspecialized" in propToArray[Int, Chunk[Int]]

    "concat.boolean" in propConcat[Boolean, Chunk[Boolean]]
    "concat.byte" in propConcat[Byte, Chunk[Byte]]
    "concat.double" in propConcat[Double, Chunk[Double]]
    "concat.long" in propConcat[Long, Chunk[Long]]
    "concat.unspecialized" in propConcat[Int, Chunk[Int]]

    "map.boolean => byte" in forAll { c: Chunk[Boolean] =>
      (c map { b => if (b) 0.toByte else 1.toByte }).toArray shouldBe (c.toArray map { b => if (b) 0.toByte else 1.toByte })
    }

    "map.long => long" in forAll { c: Chunk[Long] => (c map (1 +)).toArray shouldBe (c.toArray map (1 +)) }
  }
}
