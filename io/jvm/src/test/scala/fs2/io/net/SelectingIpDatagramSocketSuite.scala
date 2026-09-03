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

package fs2.io.net

import scala.concurrent.duration._

import cats.effect.IO
import com.comcast.ip4s._

import fs2.Fs2Suite

class SelectingIpDatagramSocketSuite extends Fs2Suite {
  private def reuse(option: SocketOption) = {
    val options = List(option)

    Network[IO].bindDatagramSocket(options = options).use { socket1 =>
      val socket2Address = SocketAddress(Ipv4Address.Wildcard, socket1.address.asIpUnsafe.port)

      Network[IO].bindDatagramSocket(socket2Address, options).use_
    }
  }

  test("SO_REUSEPORT allows concurrent use of ports") {
    reuse(SocketOption.reusePort(true))
  }

  test("SO_REUSEADDR allows concurrent use of ports") {
    reuse(SocketOption.reuseAddress(true))
  }

  test("Network allows reuse of port immediately") {
    Network[IO]
      .bindDatagramSocket()
      .use { socket1 =>
        val socket2Address = SocketAddress(Ipv4Address.Wildcard, socket1.address.asIpUnsafe.port)
        socket1.read.void.timeoutTo(10.millis, IO.unit) *> IO.pure(socket2Address)
      }
      .flatMap(socket2Address =>
        Network[IO].bindDatagramSocket(socket2Address).use(_.read.void.timeoutTo(1.milli, IO.unit))
      )
  }

}
