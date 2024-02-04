
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
import java.io.File
import scala.concurrent.duration.*

class WalkBenchmark extends Fs2IoSuite {

  private var target: Path = _

  override def beforeAll() = {
		super.beforeAll()
    val file = File.createTempFile("fs2-benchmarks-", "-walk")
    file.delete()
    file.mkdir()
    target = Path(file.toString)

    val MaxDepth = 7
    val Names = 'A'.to('E').toList.map(_.toString)

    def loop(cwd: File, depth: Int): Unit = {
      if (depth < MaxDepth) {
        Names foreach { name =>
          val sub = new File(cwd, name)
          sub.mkdir()
          loop(sub, depth + 1)
        }
      } else if (depth == MaxDepth) {
        Names foreach { name =>
          val sub = new File(cwd, name)
          sub.createNewFile()
          loop(sub, depth + 1)
        }
      }
    }

    loop(file, 0)
  }

  def time[A](f: => A): FiniteDuration = {
    val start = System.nanoTime()
    val _ = f
    (System.nanoTime() - start).nanos
  }


	test("Files.walk has similar performance to java.nio.file.Files.walk") {
    val fs2Time = time(Files[IO]
      .walk(target)
      .compile
      .count
      .unsafeRunSync())
    val nioTime = time(java.nio.file.Files.walk(target.toNioPath).count())
    val epsilon = nioTime.toNanos * 1.5
    println(s"fs2 took: ${fs2Time.toMillis} ms")
    println(s"nio took: ${nioTime.toMillis} ms")
    assert((fs2Time - nioTime).toNanos.abs < epsilon, s"fs2 time: $fs2Time, nio time: $nioTime, diff: ${fs2Time - nioTime}") 
  }
}
