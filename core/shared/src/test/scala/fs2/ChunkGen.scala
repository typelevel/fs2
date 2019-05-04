package fs2

import cats.data.Chain
import cats.laws.discipline.arbitrary._
import org.scalacheck.{ Arbitrary, Cogen, Gen, Shrink }
import org.scalacheck.util.Buildable
import Arbitrary.arbitrary
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

trait ChunkGenLowPriority1 {

  private def genSmallCollection[C[_], A: Arbitrary](implicit b: Buildable[A, C[A]], view: C[A] => Traversable[A]): Gen[C[A]] =
    for {
      n <- Gen.choose(0, 20)
      c <- Gen.containerOfN[C, A](n, arbitrary[A])
    } yield c


  def genUnspecializedChunk[A](implicit A: Arbitrary[A]): Gen[Chunk[A]] =
    Gen.frequency(
      1 -> Chunk.empty[A],
      5 -> A.arbitrary.map(a => Chunk.singleton(a)),
      10 -> genSmallCollection[Vector, A].map(Chunk.vector),
      10 -> genSmallCollection[IndexedSeq, A].map(Chunk.indexedSeq),
      10 -> genSmallCollection[Seq, A].map(Chunk.seq),
      10 -> arbitrary[Chain[A]].map(Chunk.chain),
      10 -> genSmallCollection[collection.mutable.Buffer, A].map(Chunk.buffer)
    )

  implicit def arbUnspecializedChunk[A: Arbitrary]: Arbitrary[Chunk[A]] = Arbitrary(genUnspecializedChunk[A])
}

trait ChunkGenLowPriority extends ChunkGenLowPriority1 {

  def genChunk[A](implicit A: Arbitrary[A], ct: ClassTag[A]): Gen[Chunk[A]] =
    Gen.frequency(
      8 -> genUnspecializedChunk[A],
      1 -> Gen.listOf(A.arbitrary).map(as => Chunk.array(as.toArray)),
      1 -> (for {
        as <- Gen.listOf(A.arbitrary)
        offset <- Gen.chooseNum(0, as.size / 2)
        len <- Gen.chooseNum(0, as.size - offset)
      } yield Chunk.boxed(as.toArray, offset, len))
    )

  implicit def arbChunk[A: Arbitrary: ClassTag]: Arbitrary[Chunk[A]] = Arbitrary(genChunk[A])
}

trait ChunkGen extends ChunkGenLowPriority {

  private def arrayChunk[A](build: (Array[A], Int, Int) => Chunk[A])(implicit arb: Arbitrary[A], evb: Buildable[A, Array[A]]): Gen[Chunk[A]] =
    for {
      n <- Gen.choose(0, 20)
      values <- Gen.containerOfN[Array, A](n, arb.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield build(values, offset, sz)

  private def jbufferChunk[A, B <: JBuffer](
    build: B => Chunk[A],
    native: (Int, Array[A]) => B,
    wrap: Array[A] => B
  )(implicit arb: Arbitrary[A], evb: Buildable[A, Array[A]]): Gen[Chunk[A]] =
    for {
      n <- Gen.choose(0, 20)
      values <- Gen.containerOfN[Array, A](n, arb.arbitrary)
      pos <- Gen.choose(0, n)
      lim <- Gen.choose(pos, n)
      direct <- Arbitrary.arbBool.arbitrary
      bb = if (direct) native(n, values) else wrap(values)
        _ = bb.position(pos).limit(lim)
    } yield build(bb)

  val genBooleanArrayChunk: Gen[Chunk[Boolean]] =
    arrayChunk(Chunk.booleans _)

  val genByteArrayChunk: Gen[Chunk[Byte]] =
    arrayChunk(Chunk.bytes _)

  val genByteBufferChunk: Gen[Chunk[Byte]] =
    jbufferChunk[Byte, JByteBuffer](
      Chunk.byteBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n).put(values),
      JByteBuffer.wrap _
    )

  val genByteVectorChunk: Gen[Chunk[Byte]] =
    for {
      n <- Gen.choose(0, 20)
      values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
    } yield Chunk.byteVector(ByteVector.view(values))

  val genByteChunk: Gen[Chunk[Byte]] =
    Gen.frequency(
      7 -> genChunk[Byte],
      1 -> genByteArrayChunk,
      1 -> genByteBufferChunk,
      1 -> genByteVectorChunk
    )

  implicit val arbByteChunk: Arbitrary[Chunk[Byte]] =
    Arbitrary(genByteChunk)

  val genShortArrayChunk: Gen[Chunk[Short]] =
    arrayChunk(Chunk.shorts _)

  val genShortBufferChunk: Gen[Chunk[Short]] =
    jbufferChunk[Short, JShortBuffer](
      Chunk.shortBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 2).asShortBuffer.put(values),
      JShortBuffer.wrap _
    )

  val genShortChunk: Gen[Chunk[Short]] =
    Gen.frequency(
      8 -> genChunk[Short],
      1 -> genShortArrayChunk,
      1 -> genShortBufferChunk
    )

  implicit val arbShortChunk: Arbitrary[Chunk[Short]] =
    Arbitrary(genShortChunk)

  val genLongArrayChunk: Gen[Chunk[Long]] =
    arrayChunk(Chunk.longs _)

  val genLongBufferChunk: Gen[Chunk[Long]] =
    jbufferChunk[Long, JLongBuffer](
      Chunk.longBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asLongBuffer.put(values),
      JLongBuffer.wrap _
    )

  val genLongChunk: Gen[Chunk[Long]] =
    Gen.frequency(
      8 -> genChunk[Long],
      1 -> genLongArrayChunk,
      1 -> genLongBufferChunk
    )

  implicit val arbLongChunk: Arbitrary[Chunk[Long]] =
    Arbitrary(genLongChunk)

  val genIntArrayChunk: Gen[Chunk[Int]] =
    arrayChunk(Chunk.ints _)

  val genIntBufferChunk: Gen[Chunk[Int]] =
    jbufferChunk[Int, JIntBuffer](
      Chunk.intBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asIntBuffer.put(values),
      JIntBuffer.wrap _
    )

  val genIntChunk: Gen[Chunk[Int]] =
    Gen.frequency(
      8 -> genChunk[Int],
      1 -> genIntArrayChunk,
      1 -> genIntBufferChunk
    )

  implicit val arbIntChunk: Arbitrary[Chunk[Int]] =
    Arbitrary(genIntChunk)

  val genDoubleArrayChunk: Gen[Chunk[Double]] =
    arrayChunk(Chunk.doubles _)

  val genDoubleBufferChunk: Gen[Chunk[Double]] =
    jbufferChunk[Double, JDoubleBuffer](
      Chunk.doubleBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asDoubleBuffer.put(values),
      JDoubleBuffer.wrap _
    )

  val genDoubleChunk: Gen[Chunk[Double]] =
    Gen.frequency(
      8 -> genChunk[Double],
      1 -> genDoubleArrayChunk,
      1 -> genDoubleBufferChunk
    )

  implicit val arbDoubleChunk: Arbitrary[Chunk[Double]] =
    Arbitrary(genDoubleChunk)

  val genFloatArrayChunk: Gen[Chunk[Float]] =
    arrayChunk(Chunk.floats _)

  val genFloatBufferChunk: Gen[Chunk[Float]] =
    jbufferChunk[Float, JFloatBuffer](
      Chunk.floatBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asFloatBuffer.put(values),
      JFloatBuffer.wrap _
    )

  val genFloatChunk: Gen[Chunk[Float]] =
    Gen.frequency(
      8 -> genChunk[Float],
      1 -> genFloatArrayChunk,
      1 -> genFloatBufferChunk
    )

  implicit val arbFloatChunk: Arbitrary[Chunk[Float]] =
    Arbitrary(genFloatChunk)

  val genCharBufferChunk: Gen[Chunk[Char]] =
    jbufferChunk[Char, JCharBuffer](
      Chunk.charBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asCharBuffer.put(values),
      JCharBuffer.wrap _
    )

  val genCharChunk: Gen[Chunk[Char]] =
    Gen.frequency(
      9 -> genChunk[Char],
      1 -> genCharBufferChunk
    )

  implicit val arbCharChunk: Arbitrary[Chunk[Char]] =
    Arbitrary(genCharChunk)

  implicit def cogenChunk[A: Cogen]: Cogen[Chunk[A]] =
    implicitly[Cogen[List[A]]].contramap(_.toList)

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

object ChunkGen extends ChunkGen
