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
package process

import cats.effect.IO
import fs2.io.file.Files

import scala.concurrent.duration._

class ProcessSuite extends Fs2IoSuite {

  test("echo") {
    ProcessSpawn[IO].spawn(ProcessBuilder("echo", List("hello", "world"))).use { p =>
      IO.cede *> IO.cede *> // stress the JS implementation
        p.stdout
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .assertEquals("hello world\n") *> p.exitValue.assertEquals(0)
    }
  }

  test("stdout and stderr") {
    ProcessSpawn[IO]
      .spawn(
        ProcessBuilder(
          "node",
          List("-e", "console.log('good day stdout'); console.error('how do you do stderr')")
        )
      )
      .use { p =>
        val testOut = p.stdout
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .assertEquals("good day stdout\n")

        val testErr = p.stderr
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .assertEquals("how do you do stderr\n")

        val textExit = p.exitValue.assertEquals(0)

        testOut.both(testErr).both(textExit).void
      }
  }

  test("cat") {
    ProcessSpawn[IO].spawn(ProcessBuilder("cat", Nil)).use { p =>
      val verySpecialMsg = "FS2 rocks!"
      val in = Stream.emit(verySpecialMsg).through(fs2.text.utf8.encode).through(p.stdin)
      val out = p.stdout.through(fs2.text.utf8.decode)

      out
        .concurrently(in)
        .compile
        .string
        .assertEquals(verySpecialMsg)
    }
  }

  test("working directory") {
    Files[IO].tempDirectory.use { wd =>
      ProcessSpawn[IO].spawn(ProcessBuilder("pwd", Nil).withWorkingDirectory(wd)).use { p =>
        p.stdout.through(fs2.text.utf8.decode).compile.string.assertEquals(wd.toString + "\n")
      }
    }
  }

  test("env") {
    ProcessSpawn[IO].spawn(ProcessBuilder("env", Nil).withEnv(Map("FS2" -> "ROCKS"))).use { p =>
      p.stdout
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .exists(_ == "FS2=ROCKS")
        .compile
        .onlyOrError
        .assert
    }
  }

  test("cancelation") {
    ProcessSpawn[IO]
      .spawn(ProcessBuilder("cat", Nil))
      .use { p =>
        p.stdout.compile.drain.both(p.stderr.compile.drain).both(p.exitValue).void
      }
      .timeoutTo(1.second, IO.unit) // assert that cancelation does not hang
  }

}