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
package io

import cats.effect.IO
import fs2.Fs2Suite
import fs2.io.internal.ByteChunkOps._
import fs2.internal.jsdeps.node.streamMod
import org.scalacheck.Arbitrary
import org.scalacheck.effect.PropF.forAllF
import scala.scalajs.js

import fs2.internal.jsdeps.node.bufferMod.global.Buffer

class IoSuitePlatform extends Fs2Suite {

  implicit val arbitraryBuffer = Arbitrary(
    Arbitrary.arbitrary[Array[Byte]].map(Chunk.array(_).toBuffer)
  )

  test("fromReadable") {
    forAllF { bytes: Buffer =>
      fromReadable[IO](IO(streamMod.Readable.from(bytes.asInstanceOf[js.Iterable[js.Any]]))).compile.toVector.assertEquals(bytes.toChunk.toVector)
    }
  }

}
