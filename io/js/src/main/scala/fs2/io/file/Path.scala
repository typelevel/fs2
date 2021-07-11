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
package io.file

import fs2.internal.jsdeps.node.fsMod.PathLike
import fs2.io.internal.ByteChunkOps._

sealed abstract class Path {
  private[file] def toPathLike: PathLike
}

object Path {

  def apply(path: String): Path = StringPath(path)
  def apply(path: Chunk[Byte]): Path = BufferPath(path)

  private final case class StringPath(path: String) extends Path {
    override private[file] def toPathLike: PathLike = path.asInstanceOf[PathLike]
  }

  private final case class BufferPath(path: Chunk[Byte]) extends Path {
    override private[file] def toPathLike: PathLike = path.toBuffer.asInstanceOf[PathLike]
  }

}
