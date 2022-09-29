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

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

class ChunkRuntimePlatformSuite extends Fs2Suite {
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 100 else 25)
      .withWorkers(1)

  // Override to remove from implicit scope
  override val byteChunkArbitrary = Arbitrary(???)

  def testByteChunk(
      genChunk: Gen[Chunk[Byte]],
      name: String
  ): Unit =
    group(s"$name") {
      implicit val implicitChunkArb: Arbitrary[Chunk[Byte]] = Arbitrary(genChunk)
      property("JSArrayBuffer conversion is idempotent") {
        forAll { (c: Chunk[Byte]) =>
          assertEquals(Chunk.jsArrayBuffer(c.toJSArrayBuffer), c)
        }
      }
      property("Uint8Array conversion is idempotent") {
        forAll { (c: Chunk[Byte]) =>
          assertEquals(Chunk.uint8Array(c.toUint8Array), c)
        }
      }
      property("Uint8Array conversion respects offset and length") {
        forAll[(Chunk[Byte], Int, Int), Unit](for {
          chunk <- genChunk
          offset <- Gen.chooseNum(0, chunk.size)
          length <- Gen.chooseNum(0, chunk.size - offset)
        } yield (chunk, offset, length)) { case (chunk, offset, length) =>
          val buffer = chunk.toJSArrayBuffer
          assertEquals(
            Chunk.uint8Array(new Uint8Array(buffer, offset, length)),
            Chunk.array(new Int8Array(buffer, offset, length).toArray)
          )
        }
      }
    }

  testByteChunk(byteBufferChunkGenerator, "ByteBuffer")
  testByteChunk(byteVectorChunkGenerator, "ByteVector")

}
