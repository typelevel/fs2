package fs2

import scala.collection.immutable.ArraySeq
import scala.collection.{immutable, mutable}
import scala.reflect.ClassTag
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import Arbitrary.arbitrary

class ChunkPlatformSuite extends Fs2Suite {

  private implicit def genArraySeq[A: Arbitrary: ClassTag]: Arbitrary[ArraySeq[A]] =
    Arbitrary(Gen.listOf(arbitrary[A]).map(ArraySeq.from))
  private implicit def genMutableArraySeq[A: Arbitrary: ClassTag]: Arbitrary[mutable.ArraySeq[A]] =
    Arbitrary(arbitrary[ArraySeq[A]].map(_.to(mutable.ArraySeq)))

  private def testChunkToArraySeq[A](
      testName: String,
      shouldSpecialise: Boolean,
      f: Chunk[A] => ArraySeq[A]
  )(implicit A: Arbitrary[Chunk[A]]): Unit =
    group(s"Chunk toArraySeq $testName") {
      property("values") {
        forAll { (chunk: Chunk[A]) =>
          assert(f(chunk).toVector == chunk.toVector)
        }
      }

      if (shouldSpecialise)
        property("specialised") {
          forAll { (chunk: Chunk[A]) =>
            f(chunk) match {
              case _: ArraySeq.ofRef[A] =>
                fail("Expected specialised ArraySeq but was not specialised")
              case _ => ()
            }
          }
        }
    }

  private def testChunkToArraySeqTagged[A: ClassTag](testName: String, shouldSpecialise: Boolean)(
      implicit A: Arbitrary[Chunk[A]]
  ): Unit =
    testChunkToArraySeq[A](testName, shouldSpecialise, f = _.toArraySeq[A])

  private def testChunkToArraySeqUntagged[A](
      testName: String
  )(implicit A: Arbitrary[Chunk[A]]): Unit =
    testChunkToArraySeq[A](testName, shouldSpecialise = false, f = _.toArraySeqUntagged)

  testChunkToArraySeqTagged[Int]("Int tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Double]("Double tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Long]("Long tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Float]("Float tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Char]("Char tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Byte]("Byte tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Short]("Short tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[Boolean]("Boolean tagged", shouldSpecialise = true)
  testChunkToArraySeqTagged[String]("String tagged", shouldSpecialise = false)
  testChunkToArraySeqTagged[Unit]("Unit tagged", shouldSpecialise = false)

  testChunkToArraySeqUntagged[Int]("Int untagged")
  testChunkToArraySeqUntagged[Double]("Double untagged")
  testChunkToArraySeqUntagged[Long]("Long untagged")
  testChunkToArraySeqUntagged[Float]("Float untagged")
  testChunkToArraySeqUntagged[Char]("Char untagged")
  testChunkToArraySeqUntagged[Byte]("Byte untagged")
  testChunkToArraySeqUntagged[Short]("Short untagged")
  testChunkToArraySeqUntagged[Boolean]("Boolean untagged")
  testChunkToArraySeqUntagged[Unit]("Unit untagged")
  testChunkToArraySeqUntagged[String]("String untagged")

  private def testChunkFromArraySeq[A: ClassTag: Arbitrary]: Unit = {
    val testTypeName: String = implicitly[ClassTag[A]].runtimeClass.getSimpleName

    group(s"fromArraySeq ArraySeq[$testTypeName]") {
      property("mutable") {
        forAll { (arraySeq: mutable.ArraySeq[A]) =>
          assert(Chunk.arraySeq(arraySeq).toVector == arraySeq.toVector)
        }
      }

      property("immutable") {
        forAll { (arraySeq: immutable.ArraySeq[A]) =>
          assert(Chunk.arraySeq(arraySeq).toVector == arraySeq.toVector)
        }
      }
    }
  }

  group("Chunk from ArraySeq") {
    testChunkFromArraySeq[Int]
    testChunkFromArraySeq[Double]
    testChunkFromArraySeq[Long]
    testChunkFromArraySeq[Float]
    testChunkFromArraySeq[Char]
    testChunkFromArraySeq[Byte]
    testChunkFromArraySeq[Short]
    testChunkFromArraySeq[Boolean]
    testChunkFromArraySeq[Unit]
    testChunkFromArraySeq[String]
  }

  group("Chunk iterable") {
    property("mutable ArraySeq") {
      forAll { (a: mutable.ArraySeq[String]) =>
        assert(Chunk.iterable(a).toVector == a.toVector)
      }
    }

    property("immutable ArraySeq") {
      forAll { (a: immutable.ArraySeq[String]) =>
        assert(Chunk.iterable(a).toVector == a.toVector)
      }
    }
  }
}
