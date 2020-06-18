package fs2

import org.scalatest.Assertion
import org.scalatest.prop.{CommonGenerators, Generator}

import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

class ChunkPlatformSpec extends Fs2Spec {

  // TODO should probably make a unit chunk generator in ChunkGenerators
  private implicit val genUnit: Generator[Unit] = CommonGenerators.specificValue(())

  private def assertSpecialised[A](arraySeq: ArraySeq[A]): Assertion =
    arraySeq match {
      case _: ArraySeq.ofRef[A]  => fail("Expected specialised ArraySeq but was not specialised")
      case _: ArraySeq.ofInt     => succeed
      case _: ArraySeq.ofDouble  => succeed
      case _: ArraySeq.ofLong    => succeed
      case _: ArraySeq.ofFloat   => succeed
      case _: ArraySeq.ofChar    => succeed
      case _: ArraySeq.ofByte    => succeed
      case _: ArraySeq.ofShort   => succeed
      case _: ArraySeq.ofBoolean => succeed
      case _: ArraySeq.ofUnit    => succeed
    }

  private def testChunkToArraySeq[A](testName: String, shouldSpecialise: Boolean, f: Chunk[A] => ArraySeq[A])(implicit generator: Generator[Chunk[A]]): Unit = {
    testName - {
      "values" in forAll { chunk: Chunk[A] =>
        assert(f(chunk).toVector == chunk.toVector)
      }

      if (shouldSpecialise) {
        "specialised" in forAll { chunk: Chunk[A] =>
          assertSpecialised(f(chunk))
        }
      }
    }
  }

  private def testChunkToArraySeqTagged[A : ClassTag](testName: String, shouldSpecialise: Boolean)(implicit generator: Generator[Chunk[A]]): Unit =
    testChunkToArraySeq[A](testName, shouldSpecialise, f = _.toArraySeq[A])

  private def testChunkToArraySeqUntagged[A](testName: String)(implicit generator: Generator[Chunk[A]]): Unit =
    testChunkToArraySeq[A](testName, shouldSpecialise = false, f = _.toArraySeqUntagged)

  "Chunk toArraySeq" - {
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
  }

}
