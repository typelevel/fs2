package fs2

import org.scalatest.prop.{CommonGenerators, Generator}

import scala.collection.immutable.ArraySeq
import scala.collection.{immutable, mutable}
import scala.reflect.ClassTag

class ChunkPlatformSpec extends Fs2Spec {

  private implicit val genUnit: Generator[Unit] = CommonGenerators.specificValue(())
  private implicit def genArraySeq[A: Generator: ClassTag]: Generator[ArraySeq[A]] =
    Generator.vectorGenerator[A].map(ArraySeq.from)
  private implicit def genMutableArraySeq[A: Generator: ClassTag]: Generator[mutable.ArraySeq[A]] =
    implicitly[Generator[ArraySeq[A]]].map(_.to(mutable.ArraySeq))

  private def testChunkToArraySeq[A](
      testName: String,
      shouldSpecialise: Boolean,
      f: Chunk[A] => ArraySeq[A]
  )(implicit generator: Generator[Chunk[A]]): Unit =
    s"Chunk toArraySeq $testName" - {
      "values" in forAll { chunk: Chunk[A] =>
        assert(f(chunk).toVector == chunk.toVector)
      }

      if (shouldSpecialise)
        "specialised" in forAll { chunk: Chunk[A] =>
          f(chunk) match {
            case _: ArraySeq.ofRef[A] =>
              fail("Expected specialised ArraySeq but was not specialised")
            case _ => succeed
          }
        }
    }

  private def testChunkToArraySeqTagged[A: ClassTag](testName: String, shouldSpecialise: Boolean)(
      implicit generator: Generator[Chunk[A]]
  ): Unit =
    testChunkToArraySeq[A](testName, shouldSpecialise, f = _.toArraySeq[A])

  private def testChunkToArraySeqUntagged[A](
      testName: String
  )(implicit generator: Generator[Chunk[A]]): Unit =
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

  private def testChunkFromArraySeq[A: ClassTag: Generator]: Unit = {
    val testTypeName: String = implicitly[ClassTag[A]].runtimeClass.getSimpleName

    s"fromArraySeq ArraySeq[$testTypeName]" - {
      "mutable" in forAll { arraySeq: mutable.ArraySeq[A] =>
        assert(Chunk.arraySeq(arraySeq).toVector == arraySeq.toVector)
      }

      "immutable" in forAll { arraySeq: immutable.ArraySeq[A] =>
        assert(Chunk.arraySeq(arraySeq).toVector == arraySeq.toVector)
      }
    }
  }

  "Chunk from ArraySeq" - {
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

  "Chunk iterable" - {
    "mutable ArraySeq" in forAll { a: mutable.ArraySeq[String] =>
      assert(Chunk.iterable(a).toVector == a.toVector)
    }

    "immutable ArraySeq" in forAll { a: immutable.ArraySeq[String] =>
      assert(Chunk.iterable(a).toVector == a.toVector)
    }
  }

}
