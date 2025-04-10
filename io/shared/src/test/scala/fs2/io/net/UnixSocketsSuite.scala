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

import scala.concurrent.duration._

import cats.effect.IO
import com.comcast.ip4s.UnixSocketAddress

class UnixSocketsSuite extends Fs2Suite with UnixSocketsSuitePlatform {

  protected def testProvider(provider: String, sockets: UnixSocketsProvider[IO]) =
    test(s"echoes - $provider") {
      val address = UnixSocketAddress("fs2-unix-sockets-test.sock")

      val server = Stream.resource(sockets.bind(address, Nil))
        .flatMap(_.accept)
        .map { client =>
          client.reads.through(client.writes)
        }
        .parJoinUnbounded

      def client(msg: Chunk[Byte]) = sockets.connect(address, Nil).use { server =>
        server.write(msg) *> server.endOfOutput *> server.reads.compile
          .to(Chunk)
          .map(read => assertEquals(read, msg))
      }

      val clients = (0 until 100).map(b => client(Chunk.singleton(b.toByte)))

      (Stream.sleep_[IO](1.second) ++ Stream.emits(clients).evalMap(identity))
        .concurrently(server)
        .compile
        .drain
    }
}
