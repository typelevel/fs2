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
package net
package udp

import cats.effect.IO

import scala.concurrent.duration._

trait UdpSuitePlatform { self: UdpSuite =>
  val concurrentBindOptionsPlatform =
    List(SocketOption.reuseAddress(true), SocketOption.reusePort(true))

  test("Network allows reuse of port immediately") {
    // Note: even if the JVM released the port's channel, the OS may not make it available
    // immediately, so SocketOption.reuseAddress(true) is needed.
    network
      .bindDatagramSocket(options = List(SocketOption.reuseAddress(true)))
      .use { socket1 =>
        socket1.read.void.timeoutTo(10.millis, IO.unit) *> IO.pure(socket1.address)
      }
      .flatMap(socket2Address =>
        network
          .bindDatagramSocket(socket2Address, List(SocketOption.reuseAddress(true)))
          .use(_.read.void.timeoutTo(1.milli, IO.unit))
      )
  }
}
