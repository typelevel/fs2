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
package io.net

import cats.effect.IO
import com.comcast.ip4s.UnixSocketAddress

trait UnixSocketsSuitePlatform { self: UnixSocketsSuite =>
  private def testBindFailureKeepsPath(
      provider: String,
      sockets: UnixSocketsProvider[IO]
  ) =
    test(s"does not delete the path when binding fails - $provider") {
      val address = UnixSocketAddress(s"fs2-unix-sockets-bind-failure-$provider.sock")
      val path = java.nio.file.Paths.get(address.path)
      java.nio.file.Files
        .write(path, "not a socket".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      for {
        result <- sockets.bindUnix(address, Nil).use(_ => IO.unit).attempt
        exists = java.nio.file.Files.exists(path)
        _ <- IO(java.nio.file.Files.deleteIfExists(path))
      } yield {
        assert(result.isLeft, "binding to an existing path should fail")
        assert(exists, "a failed bind must not delete the existing path")
      }
    }

  if (JdkUnixSocketsProvider.supported) {
    testProvider("jdk", JdkUnixSocketsProvider.forAsyncAndFiles[IO])
    testBindFailureKeepsPath("jdk", JdkUnixSocketsProvider.forAsyncAndFiles[IO])
  }
  // jnr native library is not available on Windows
  if (
    JnrUnixSocketsProvider.supported && !System
      .getProperty("os.name")
      .toLowerCase
      .contains("windows")
  ) {
    testProvider("jnr", JnrUnixSocketsProvider.forAsyncAndFiles[IO])
    testBindFailureKeepsPath("jnr", JnrUnixSocketsProvider.forAsyncAndFiles[IO])
  }
}
