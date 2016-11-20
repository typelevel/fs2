/* The arbitrary ByteVectors are taken from scodec.bits.Arbitraries */
package fs2
package scodec

import java.nio.ByteBuffer

import scala.{Stream => SStream}
import _root_.scodec.bits.ByteVector

import org.scalacheck.{Gen, Arbitrary, Shrink}
import org.scalacheck.Arbitrary.arbitrary

object ArbitraryScodecBits {
  def standardByteVectors(maxSize: Int): Gen[ByteVector] = for {
    size <- Gen.choose(0, maxSize)
    bytes <- Gen.listOfN(size, Gen.choose(0, 255))
  } yield ByteVector(bytes: _*)

  val sliceByteVectors: Gen[ByteVector] = for {
    bytes <- arbitrary[Array[Byte]]
    toDrop <- Gen.choose(0, bytes.size)
  } yield ByteVector.view(bytes).drop(toDrop.toLong)

  def genSplitBytes(g: Gen[ByteVector]) = for {
    b <- g
    n <- Gen.choose(0, b.size+1)
  } yield {
    b.take(n) ++ b.drop(n)
  }

  def genConcatBytes(g: Gen[ByteVector]) =
    g.map { b => b.toIndexedSeq.foldLeft(ByteVector.empty)(_ :+ _) }

  def genByteBufferVectors(maxSize: Int): Gen[ByteVector] = for {
    size <- Gen.choose(0, maxSize)
    bytes <- Gen.listOfN(size, Gen.choose(0, 255))
  } yield ByteVector.view(ByteBuffer.wrap(bytes.map(_.toByte).toArray))

  val byteVectors: Gen[ByteVector] = Gen.oneOf(
    standardByteVectors(100),
    genConcatBytes(standardByteVectors(100)),
    sliceByteVectors,
    genSplitBytes(sliceByteVectors),
    genSplitBytes(genConcatBytes(standardByteVectors(500))),
    genByteBufferVectors(100))

  implicit val arbitraryByteVectors: Arbitrary[ByteVector] =
    Arbitrary(byteVectors)

  val byteVectorChunks: Gen[ByteVectorChunk] =
    byteVectors.map(ByteVectorChunk(_))

  implicit val aribtraryByteVectorChunks: Arbitrary[ByteVectorChunk] =
    Arbitrary(byteVectorChunks)

  implicit val shrinkByteVector: Shrink[ByteVector] =
    Shrink[ByteVector] { b =>
      if (b.nonEmpty)
        SStream.iterate(b.take(b.size / 2))(b2 => b2.take(b2.size / 2)).takeWhile(_.nonEmpty) ++ SStream(ByteVector.empty)
      else SStream.empty
    }
}
