/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.data.Chain
import scala.reflect.ClassTag
import scodec.bits.ByteVector
import java.nio.{Buffer => JBuffer, ByteBuffer => JByteBuffer, CharBuffer => JCharBuffer}

import org.scalacheck.{Arbitrary, Cogen, Gen}
import Arbitrary.arbitrary

import fs2.internal.makeArrayBuilder

trait ChunkGeneratorsLowPriority1 extends MiscellaneousGenerators {

  implicit def unspecializedChunkArbitrary[A](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] =
    Arbitrary(unspecializedChunkGenerator(A.arbitrary))

  def unspecializedChunkGenerator[A](genA: Gen[A]): Gen[Chunk[A]] = {
    val gen = Gen.frequency(
      1 -> Gen.const(Chunk.empty[A]),
      5 -> genA.map(Chunk.singleton),
      10 -> Gen.listOf(genA).map(Chunk.seq),
      10 -> Gen.listOf(genA).map(_.toVector).map(Chunk.vector),
      10 -> Gen
        .listOf(genA)
        .map { as =>
          val builder = makeArrayBuilder[Any]
          builder ++= as
          Chunk.array(builder.result()).asInstanceOf[Chunk[A]]
        },
      10 -> Gen
        .listOf(genA)
        .map(_.toVector)
        .map(as => Chunk.chain(Chain.fromSeq(as))) // TODO Add variety in Chain
    )

    Gen.frequency(
      20 -> Gen.resize(20, gen),
      5 -> gen,
      2 -> Gen.resize(300, gen)
    )
  }
}

trait ChunkGeneratorsLowPriority extends ChunkGeneratorsLowPriority1 {
  implicit def chunkArbitrary[A](implicit A: Arbitrary[A], ct: ClassTag[A]): Arbitrary[Chunk[A]] =
    Arbitrary(chunkGenerator(A.arbitrary))
  def chunkGenerator[A](genA: Gen[A])(implicit ct: ClassTag[A]): Gen[Chunk[A]] =
    Gen.frequency(
      8 -> unspecializedChunkGenerator(genA),
      1 -> Gen.listOf(genA).map(as => Chunk.array(as.toArray)),
      1 -> (for {
        as <- Gen.listOf(genA)
        offset <- Gen.chooseNum(0, as.size / 2)
        len <- Gen.chooseNum(0, as.size - offset)
      } yield Chunk.array(as.toArray, offset, len))
    )
}

trait ChunkGenerators extends ChunkGeneratorsLowPriority with ChunkGeneratorsCompat {
  private def arrayChunkGenerator[A](genA: Gen[A])(implicit ct: ClassTag[A]): Gen[Chunk[A]] =
    for {
      values <- Gen.listOf(genA).map(_.toArray)
      offset <- Gen.chooseNum(0, values.size)
      sz <- Gen.chooseNum(0, values.size - offset)
    } yield Chunk.array(values, offset, sz)

  private def jbufferChunkGenerator[A, B <: JBuffer](
      genA: Gen[A],
      build: B => Chunk[A],
      native: (Int, Array[A]) => B,
      wrap: Array[A] => B
  )(implicit cta: ClassTag[A]): Gen[Chunk[A]] =
    for {
      values <- Gen.listOf(genA).map(_.toArray)
      n = values.size
      pos <- Gen.chooseNum(0, n)
      lim <- Gen.chooseNum(pos, n)
      direct <- arbitrary[Boolean]
      bb = if (direct) native(n, values) else wrap(values)
      _ = bb.position(pos).limit(lim)
    } yield build(bb)

  val byteBufferChunkGenerator: Gen[Chunk[Byte]] =
    jbufferChunkGenerator[Byte, JByteBuffer](
      arbitrary[Byte],
      Chunk.byteBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n).put(values),
      JByteBuffer.wrap _
    )

  val byteVectorChunkGenerator: Gen[Chunk[Byte]] =
    for {
      values <- Gen.listOf(arbitrary[Byte]).map(_.toArray)
    } yield Chunk.byteVector(ByteVector.view(values))

  val byteChunkGenerator: Gen[Chunk[Byte]] =
    Gen.frequency(
      7 -> chunkGenerator(arbitrary[Byte]),
      1 -> byteBufferChunkGenerator,
      1 -> byteVectorChunkGenerator
    )
  implicit val byteChunkArbitrary: Arbitrary[Chunk[Byte]] =
    Arbitrary(byteChunkGenerator)

  val intArrayChunkGenerator: Gen[Chunk[Int]] =
    arrayChunkGenerator(arbitrary[Int])

  val intChunkGenerator: Gen[Chunk[Int]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Int]),
      1 -> intArrayChunkGenerator
    )
  implicit val intChunkArbitrary: Arbitrary[Chunk[Int]] =
    Arbitrary(intChunkGenerator)

  val charBufferChunkGenerator: Gen[Chunk[Char]] =
    jbufferChunkGenerator[Char, JCharBuffer](
      arbitrary[Char],
      Chunk.charBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asCharBuffer.put(values),
      JCharBuffer.wrap _
    )

  val charChunkGenerator: Gen[Chunk[Char]] =
    Gen.frequency(
      9 -> chunkGenerator(arbitrary[Char]),
      1 -> charBufferChunkGenerator
    )
  implicit val charChunkArbitrary: Arbitrary[Chunk[Char]] =
    Arbitrary(charChunkGenerator)

  implicit def cogenChunk[A: Cogen]: Cogen[Chunk[A]] =
    Cogen[List[A]].contramap(_.toList)
}

object ChunkGenerators extends ChunkGenerators
