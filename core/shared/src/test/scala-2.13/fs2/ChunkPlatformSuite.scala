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

import scala.annotation.nowarn
import scala.collection.immutable.ArraySeq
import scala.collection.{immutable, mutable}
import scala.reflect.ClassTag
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import Arbitrary.arbitrary

class ChunkPlatformSuite extends Fs2Suite {

  @nowarn("cat=unused")
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
          assertEquals(f(chunk).toVector, chunk.toVector)
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
          assertEquals(Chunk.arraySeq(arraySeq).toVector, arraySeq.toVector)
        }
      }

      property("immutable") {
        forAll { (arraySeq: immutable.ArraySeq[A]) =>
          assertEquals(Chunk.arraySeq(arraySeq).toVector, arraySeq.toVector)
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
        assertEquals(Chunk.iterable(a).toVector, a.toVector)
      }
    }

    property("immutable ArraySeq") {
      forAll { (a: immutable.ArraySeq[String]) =>
        assertEquals(Chunk.iterable(a).toVector, a.toVector)
      }
    }
  }
}
