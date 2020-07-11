package fs2

import cats.data.Chain
import scala.reflect.ClassTag
import scala.collection.immutable.{Stream => StdStream}
import scodec.bits.ByteVector
import java.nio.{
  Buffer => JBuffer,
  CharBuffer => JCharBuffer,
  ByteBuffer => JByteBuffer,
  ShortBuffer => JShortBuffer,
  IntBuffer => JIntBuffer,
  DoubleBuffer => JDoubleBuffer,
  LongBuffer => JLongBuffer,
  FloatBuffer => JFloatBuffer
}

import org.scalacheck.{Arbitrary, Cogen, Gen, Shrink}
import Arbitrary.arbitrary

trait ChunkGeneratorsLowPriority1 extends MiscellaneousGenerators {

  implicit def unspecializedChunkArbitrary[A](implicit A: Arbitrary[A]): Arbitrary[Chunk[A]] =
    Arbitrary(unspecializedChunkGenerator(A.arbitrary))

  def unspecializedChunkGenerator[A](genA: Gen[A]): Gen[Chunk[A]] =
    Gen.frequency(
      1 -> Gen.const(Chunk.empty[A]),
      5 -> genA.map(Chunk.singleton),
      10 -> smallLists(genA).map(Chunk.seq),
      10 -> smallLists(genA).map(_.toVector).map(Chunk.vector),
      10 -> smallLists(genA)
        .map(_.toVector)
        .map(as => Chunk.buffer(collection.mutable.Buffer.empty ++= as)),
      10 -> smallLists(genA)
        .map(_.toVector)
        .map(as => Chunk.chain(Chain.fromSeq(as))) // TODO Add variety in Chain
    )
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
      } yield Chunk.boxed(as.toArray, offset, len))
    )
}

trait ChunkGenerators extends ChunkGeneratorsLowPriority {
  private def arrayChunkGenerator[A](genA: Gen[A])(
      build: (Array[A], Int, Int) => Chunk[A]
  )(implicit ct: ClassTag[A]): Gen[Chunk[A]] =
    for {
      values <- smallLists(genA).map(_.toArray)
      offset <- Gen.chooseNum(0, values.size)
      sz <- Gen.chooseNum(0, values.size - offset)
    } yield build(values, offset, sz)

  private def jbufferChunkGenerator[A, B <: JBuffer](
      genA: Gen[A],
      build: B => Chunk[A],
      native: (Int, Array[A]) => B,
      wrap: Array[A] => B
  )(implicit cta: ClassTag[A]): Gen[Chunk[A]] =
    for {
      values <- smallLists(genA).map(_.toArray)
      n = values.size
      pos <- Gen.chooseNum(0, n)
      lim <- Gen.chooseNum(pos, n)
      direct <- arbitrary[Boolean]
      bb = if (direct) native(n, values) else wrap(values)
      _ = bb.position(pos).limit(lim)
    } yield build(bb)

  val booleanArrayChunkGenerator: Gen[Chunk[Boolean]] =
    arrayChunkGenerator(arbitrary[Boolean])(Chunk.booleans _)

  val byteArrayChunkGenerator: Gen[Chunk[Byte]] =
    arrayChunkGenerator(arbitrary[Byte])(Chunk.bytes _)

  val byteBufferChunkGenerator: Gen[Chunk[Byte]] =
    jbufferChunkGenerator[Byte, JByteBuffer](
      arbitrary[Byte],
      Chunk.byteBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n).put(values),
      JByteBuffer.wrap _
    )

  val byteVectorChunkGenerator: Gen[Chunk[Byte]] =
    for {
      values <- smallLists(arbitrary[Byte]).map(_.toArray)
    } yield Chunk.byteVector(ByteVector.view(values))

  val byteChunkGenerator: Gen[Chunk[Byte]] =
    Gen.frequency(
      7 -> chunkGenerator(arbitrary[Byte]),
      1 -> byteArrayChunkGenerator,
      1 -> byteBufferChunkGenerator,
      1 -> byteVectorChunkGenerator
    )
  implicit val byteChunkArbitrary: Arbitrary[Chunk[Byte]] =
    Arbitrary(byteChunkGenerator)

  val shortArrayChunkGenerator: Gen[Chunk[Short]] =
    arrayChunkGenerator(arbitrary[Short])(Chunk.shorts _)

  val shortBufferChunkGenerator: Gen[Chunk[Short]] =
    jbufferChunkGenerator[Short, JShortBuffer](
      arbitrary[Short],
      Chunk.shortBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 2).asShortBuffer.put(values),
      JShortBuffer.wrap _
    )

  val shortChunkGenerator: Gen[Chunk[Short]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Short]),
      1 -> shortArrayChunkGenerator,
      1 -> shortBufferChunkGenerator
    )
  implicit val shortChunkArbitrary: Arbitrary[Chunk[Short]] =
    Arbitrary(shortChunkGenerator)

  val longArrayChunkGenerator: Gen[Chunk[Long]] =
    arrayChunkGenerator(arbitrary[Long])(Chunk.longs _)

  val longBufferChunkGenerator: Gen[Chunk[Long]] =
    jbufferChunkGenerator[Long, JLongBuffer](
      arbitrary[Long],
      Chunk.longBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asLongBuffer.put(values),
      JLongBuffer.wrap _
    )

  val longChunkGenerator: Gen[Chunk[Long]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Long]),
      1 -> longArrayChunkGenerator,
      1 -> longBufferChunkGenerator
    )
  implicit val longChunkArbitrary: Arbitrary[Chunk[Long]] =
    Arbitrary(longChunkGenerator)

  val intArrayChunkGenerator: Gen[Chunk[Int]] =
    arrayChunkGenerator(arbitrary[Int])(Chunk.ints _)

  val intBufferChunkGenerator: Gen[Chunk[Int]] =
    jbufferChunkGenerator[Int, JIntBuffer](
      arbitrary[Int],
      Chunk.intBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asIntBuffer.put(values),
      JIntBuffer.wrap _
    )

  val intChunkGenerator: Gen[Chunk[Int]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Int]),
      1 -> intArrayChunkGenerator,
      1 -> intBufferChunkGenerator
    )
  implicit val intChunkArbitrary: Arbitrary[Chunk[Int]] =
    Arbitrary(intChunkGenerator)

  val doubleArrayChunkGenerator: Gen[Chunk[Double]] =
    arrayChunkGenerator(arbitrary[Double])(Chunk.doubles _)

  val doubleBufferChunkGenerator: Gen[Chunk[Double]] =
    jbufferChunkGenerator[Double, JDoubleBuffer](
      arbitrary[Double],
      Chunk.doubleBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asDoubleBuffer.put(values),
      JDoubleBuffer.wrap _
    )

  val doubleChunkGenerator: Gen[Chunk[Double]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Double]),
      1 -> doubleArrayChunkGenerator,
      1 -> doubleBufferChunkGenerator
    )
  implicit val doubleChunkArbitrary: Arbitrary[Chunk[Double]] =
    Arbitrary(doubleChunkGenerator)

  val floatArrayChunkGenerator: Gen[Chunk[Float]] =
    arrayChunkGenerator(arbitrary[Float])(Chunk.floats _)

  val floatBufferChunkGenerator: Gen[Chunk[Float]] =
    jbufferChunkGenerator[Float, JFloatBuffer](
      arbitrary[Float],
      Chunk.floatBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asFloatBuffer.put(values),
      JFloatBuffer.wrap _
    )

  val floatChunkGenerator: Gen[Chunk[Float]] =
    Gen.frequency(
      8 -> chunkGenerator(arbitrary[Float]),
      1 -> floatArrayChunkGenerator,
      1 -> floatBufferChunkGenerator
    )
  implicit val floatChunkArbitrary: Arbitrary[Chunk[Float]] =
    Arbitrary(floatChunkGenerator)

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

  implicit def shrinkChunk[A]: Shrink[Chunk[A]] =
    Shrink[Chunk[A]](c => removeChunks(c.size, c))

  // The removeChunks function and the interleave function were ported from Scalacheck,
  // from Shrink.scala, licensed under a Revised BSD license.
  //
  // /*-------------------------------------------------------------------------*\
  // **  ScalaCheck                                                             **
  // **  Copyright (c) 2007-2016 Rickard Nilsson. All rights reserved.          **
  // **  http://www.scalacheck.org                                              **
  // **                                                                         **
  // **  This software is released under the terms of the Revised BSD License.  **
  // **  There is NO WARRANTY. See the file LICENSE for the full text.          **
  // \*------------------------------------------------------------------------ */

  private def removeChunks[A](size: Int, xs: Chunk[A]): StdStream[Chunk[A]] =
    if (xs.isEmpty) StdStream.empty
    else if (xs.size == 1) StdStream(Chunk.empty)
    else {
      val n1 = size / 2
      val n2 = size - n1
      lazy val xs1 = xs.take(n1)
      lazy val xs2 = xs.drop(n1)
      lazy val xs3 =
        for (ys1 <- removeChunks(n1, xs1) if !ys1.isEmpty) yield Chunk.concat(List(ys1, xs2))
      lazy val xs4 =
        for (ys2 <- removeChunks(n2, xs2) if !ys2.isEmpty) yield Chunk.concat(List(xs1, ys2))

      StdStream.cons(xs1, StdStream.cons(xs2, interleave(xs3, xs4)))
    }

  private def interleave[A](xs: StdStream[A], ys: StdStream[A]): StdStream[A] =
    if (xs.isEmpty) ys else if (ys.isEmpty) xs else xs.head +: ys.head +: (xs.tail ++ ys.tail)
}

object ChunkGenerators extends ChunkGenerators
