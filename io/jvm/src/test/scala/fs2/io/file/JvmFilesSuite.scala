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
package file

import cats.effect.IO
import cats.syntax.all._
import java.nio.file.{Files => JFiles}

class JvmFilesSuite extends Fs2Suite with BaseFileSuite {
  group("support non-default filesystems") {
    import com.google.common.jimfs.{Configuration, Jimfs}

    test("copy from local filesystem to in-memory filesystem") {
      val fs = Jimfs.newFileSystem(Configuration.unix)
      tempFile.evalMap(modify).use { src =>
        val dst = Path.fromNioPath(fs.getPath("copied"))
        Files[IO].copy(src, dst) *> (Files[IO].size(src), Files[IO].size(dst)).tupled.map {
          case (srcSize, dstSize) => assertEquals(dstSize, srcSize)
        }
      }
    }
  }

  test("traversal order in walk is compatible with NIO") {
    tempFilesHierarchy.use { dir =>
      val nioWalk = IO {
        val paths = JFiles
          .walk(dir.toNioPath)
          .iterator
        var out: List[Path] = Nil
        while (paths.hasNext) {
          val path = paths.next
          out = out :+ Path.fromNioPath(path)
        }
        out
      }

      val walk = Files[IO].walk(dir).compile.toList

      (walk, nioWalk).mapN(assertEquals(_, _))
    }
  }

}
