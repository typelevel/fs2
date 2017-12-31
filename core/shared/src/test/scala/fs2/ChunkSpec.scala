package fs2

import java.nio.ByteBuffer
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
      // Chunk.seq(c).iterator.toList shouldBe c.iterator.toList
      // Chunk.indexedSeq(c).iterator.toList shouldBe c.iterator.toList
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

    implicit val arbByteBufferChunk: Arbitrary[Chunk.ByteBuffer] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
        pos <- Gen.choose(0, n)
        lim <- Gen.choose(pos, n)
        direct <- Arbitrary.arbBool.arbitrary
        bb = if (direct) ByteBuffer.allocateDirect(n).put(values) else ByteBuffer.wrap(values)
        _ = bb.position(pos).limit(lim)
      } yield Chunk.ByteBuffer(bb)
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
    "size.byteBuffer" in propSize[Byte, Chunk.ByteBuffer]
    "size.double" in propSize[Double, Chunk[Double]]
    "size.long" in propSize[Long, Chunk[Long]]
    "size.unspecialized" in propSize[Int, Chunk[Int]]

    "take.boolean" in propTake[Boolean, Chunk[Boolean]]
    "take.byte" in propTake[Byte, Chunk[Byte]]
    "take.byteBuffer" in propTake[Byte, Chunk.ByteBuffer]    
    "take.double" in propTake[Double, Chunk[Double]]
    "take.long" in propTake[Long, Chunk[Long]]
    "take.unspecialized" in propTake[Int, Chunk[Int]]

    "drop.boolean" in propDrop[Boolean, Chunk[Boolean]]
    "drop.byte" in propDrop[Byte, Chunk[Byte]]
    "drop.byteBuffer" in propDrop[Byte, Chunk.ByteBuffer]
    "drop.double" in propDrop[Double, Chunk[Double]]
    "drop.long" in propDrop[Long, Chunk[Long]]
    "drop.unspecialized" in propDrop[Int, Chunk[Int]]

    "isempty.boolean" in propIsEmpty[Boolean, Chunk[Boolean]]
    "isempty.byte" in propIsEmpty[Byte, Chunk[Byte]]
    "isempty.byteBuffer" in propIsEmpty[Byte, Chunk.ByteBuffer]
    "isempty.double" in propIsEmpty[Double, Chunk[Double]]
    "isempty.long" in propIsEmpty[Long, Chunk[Long]]
    "isempty.unspecialized" in propIsEmpty[Int, Chunk[Int]]

    "toarray.boolean" in propToArray[Boolean, Chunk[Boolean]]
    "toarray.byte" in propToArray[Byte, Chunk[Byte]]
    "toarray.byteBuffer" in propToArray[Byte, Chunk.ByteBuffer]
    "toarray.double" in propToArray[Double, Chunk[Double]]
    "toarray.long" in propToArray[Long, Chunk[Long]]
    "toarray.unspecialized" in propToArray[Int, Chunk[Int]]

    "tobytebuffer.byte" in propToByteBuffer[Chunk[Byte]]
    "tobytebuffer.byteBuffer" in propToByteBuffer[Chunk.ByteBuffer]

    // "map.boolean => byte" in forAll { c: Chunk[Boolean] =>
    //   (c map { b => if (b) 0.toByte else 1.toByte }).toArray shouldBe (c.toArray map { b => if (b) 0.toByte else 1.toByte })
    // }

    // "map.long => long" in forAll { c: Chunk[Long] => (c map (1 +)).toArray shouldBe (c.toArray map (1 +)) }
  }
}
