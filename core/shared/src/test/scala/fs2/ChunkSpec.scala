package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{ MonadTests, TraverseTests }
import cats.implicits._
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
import org.scalacheck.{ Arbitrary, Cogen, Gen }
import org.scalacheck.util.Buildable
import scala.reflect.ClassTag
import scodec.bits.ByteVector

import TestUtil._
import ChunkProps._

class ChunkSpec extends Fs2Spec {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = if (isJVM) 300 else 50, workers = 1)

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
    }
  }

  def simpleArbChunk[A](cons: (Array[A], Int, Int) => Chunk[A])(implicit arb: Arbitrary[A], evb: Buildable[A, Array[A]]): Arbitrary[Chunk[A]] =
    Arbitrary {
      for {
        n <- Gen.choose(0, 20)
        values <- Gen.containerOfN[Array, A](n, arb.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield cons(values, offset, sz)
    }


  def bufferArbChunk[A, B <: JBuffer](
    cons: B => Chunk[A],
    native: (Int, Array[A]) => B,
    wrap: Array[A] => B
  )(implicit arb: Arbitrary[A], evb: Buildable[A, Array[A]]): Arbitrary[Chunk[A]] =
    Arbitrary {
      for {
        n <- Gen.choose(0, 20)
        values <- Gen.containerOfN[Array, A](n, arb.arbitrary)
        pos <- Gen.choose(0, n)
        lim <- Gen.choose(pos, n)
        direct <- Arbitrary.arbBool.arbitrary
        bb = if (direct) native(n, values) else wrap(values)
         _ = bb.position(pos).limit(lim)
      } yield cons(bb)
    }

  implicit val arbBooleanChunk: Arbitrary[Chunk[Boolean]] =
    simpleArbChunk(Chunk.booleans _)

  implicit val arbByteChunk: Arbitrary[Chunk[Byte]] =
    simpleArbChunk(Chunk.bytes _)

  val arbByteBufferChunk: Arbitrary[Chunk[Byte]] =
    bufferArbChunk[Byte, JByteBuffer](
      Chunk.byteBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n).put(values),
      JByteBuffer.wrap _
    )

  val arbByteVectorChunk: Arbitrary[Chunk[Byte]] = Arbitrary {
    for {
      n <- Gen.choose(0, 20)
      values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
    } yield Chunk.byteVector(ByteVector.view(values))
  }

  val arbFloatBufferChunk: Arbitrary[Chunk[Float]] =
    bufferArbChunk[Float, JFloatBuffer](
      Chunk.floatBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asFloatBuffer.put(values),
      JFloatBuffer.wrap _
    )


  val arbShortBufferChunk: Arbitrary[Chunk[Short]] =
    bufferArbChunk[Short, JShortBuffer](
      Chunk.shortBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 2).asShortBuffer.put(values),
      JShortBuffer.wrap _
    )


  val arbLongBufferChunk: Arbitrary[Chunk[Long]] =
    bufferArbChunk[Long, JLongBuffer](
      Chunk.longBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asLongBuffer.put(values),
      JLongBuffer.wrap _
    )

  val arbIntBufferChunk: Arbitrary[Chunk[Int]] =
    bufferArbChunk[Int, JIntBuffer](
      Chunk.intBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asIntBuffer.put(values),
      JIntBuffer.wrap _
    )

  val arbDoubleBufferChunk: Arbitrary[Chunk[Double]] =
    bufferArbChunk[Double, JDoubleBuffer](
      Chunk.doubleBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 8).asDoubleBuffer.put(values),
      JDoubleBuffer.wrap _
    )

  val arbCharBufferChunk: Arbitrary[Chunk[Char]] =
    bufferArbChunk[Char, JCharBuffer](
      Chunk.charBuffer _,
      (n, values) => JByteBuffer.allocateDirect(n * 4).asCharBuffer.put(values),
      JCharBuffer.wrap _
    )

  implicit val arbShortChunk: Arbitrary[Chunk[Short]] =
    simpleArbChunk(Chunk.shorts _)

  implicit val arbIntChunk: Arbitrary[Chunk[Int]] =
    simpleArbChunk(Chunk.ints _)

  implicit val arbLongChunk: Arbitrary[Chunk[Long]] =
    simpleArbChunk(Chunk.longs _)

  implicit val arbFloatChunk: Arbitrary[Chunk[Float]] =
    simpleArbChunk(Chunk.floats _)


  implicit val arbDoubleChunk: Arbitrary[Chunk[Double]] =
    simpleArbChunk(Chunk.doubles _)

  def testChunk[A: Arbitrary: ClassTag: Cogen: CommutativeMonoid: Eq](name: String, of: String, testTraverse: Boolean = true)(implicit C: Arbitrary[Chunk[A]]): Unit = {
    s"$name" - {
      "size" in propSize[A, Chunk[A]]
      "take" in propTake[A, Chunk[A]]
      "drop" in propDrop[A, Chunk[A]]
      "isEmpty" in propIsEmpty[A, Chunk[A]]
      "toArray" in propToArray[A, Chunk[A]]
      "copyToArray" in propCopyToArray[A, Chunk[A]]
      "concat" in propConcat[A, Chunk[A]]

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        "toByteBuffer.byte" in propToByteBuffer[Chunk[Byte]]

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll(s"Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])

      if (testTraverse)
        checkAll(s"Traverse[Chunk]", TraverseTests[Chunk].traverse[A, A, A, A, Option, Option])
    }
  }

  implicit val commutativeMonoidForChar = new CommutativeMonoid[Char] {
    def combine(x: Char, y: Char): Char = (x + y).toChar
    def empty: Char = 0
  }

  testChunk[Byte]("Bytes", "Byte")
  testChunk[Short]("Shorts", "Short")
  testChunk[Int]("Ints", "Int")
  testChunk[Long]("Longs", "Long")
  // Don't test traverse on Double or Float. They have naughty monoids.
  testChunk[Double]("Doubles", "Double", false)
  testChunk[Float]("Floats", "Float", false)
  testChunk[Char]("Unspecialized", "Char")
  testChunk[Byte]("ByteBuffer", "Byte")(implicitly, implicitly, implicitly, implicitly, implicitly, arbByteBufferChunk)
  testChunk[Byte]("ByteVector", "Byte")(implicitly, implicitly, implicitly, implicitly, implicitly, arbByteVectorChunk)
  testChunk[Int]("IntBuffer", "Int")(implicitly, implicitly, implicitly, implicitly, implicitly, arbIntBufferChunk)
  testChunk[Float]("FloatBuffer", "Float", false)(implicitly, implicitly, implicitly, implicitly, implicitly, arbFloatBufferChunk)
  testChunk[Long]("LongBuffer", "Long")(implicitly, implicitly, implicitly, implicitly, implicitly, arbLongBufferChunk)
  testChunk[Short]("ShortBuffer", "Short")(implicitly, implicitly, implicitly, implicitly, implicitly, arbShortBufferChunk)
  testChunk[Char]("CharBuffer", "Char")(implicitly, implicitly, implicitly, implicitly, implicitly, arbCharBufferChunk)
  testChunk[Double]("DoubleBuffer", "Double", false)(implicitly, implicitly, implicitly, implicitly, implicitly, arbDoubleBufferChunk)
}
