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
import cats.syntax.all._
import com.comcast.ip4s.UnixSocketAddress

trait UnixSocketsSuitePlatform { self: UnixSocketsSuite =>
  if (JdkUnixSocketsProvider.supported)
    testProvider("jdk", JdkUnixSocketsProvider.forAsyncAndFiles[IO])
  if (JnrUnixSocketsProvider.supported)
    testProvider("jnr", JnrUnixSocketsProvider.forAsyncAndFiles[IO])

  if (JdkUnixSocketsProvider.supported) {
    // Network[IO] picks the selecting provider, as the IO runtime has a Selector
    testProvider(
      "selecting",
      new UnixSocketsProvider[IO] {
        def connectUnix(address: UnixSocketAddress, options: List[SocketOption]) =
          Network[IO].connect(address, options)
        def bindUnix(address: UnixSocketAddress, options: List[SocketOption]) =
          Network[IO].bind(address, options)
      }
    )

    test("Network uses selecting sockets for unix sockets when a Selector is available") {
      val address = UnixSocketAddress("fs2-unix-sockets-selecting-test.sock")
      Network[IO]
        .bind(address, Nil)
        .flatMap { server =>
          Network[IO]
            .connect(address, Nil)
            .product(server.accept.take(1).compile.resource.lastOrError)
        }
        .use { case (client, accepted) =>
          IO {
            assertEquals(client.getClass.getSimpleName, "SelectingSocket")
            assertEquals(accepted.getClass.getSimpleName, "SelectingSocket")
          }
        }
    }
  }
}
