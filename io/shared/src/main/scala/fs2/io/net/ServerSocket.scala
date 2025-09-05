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

import com.comcast.ip4s.GenSocketAddress

/** Represents a bound TCP server socket.
  *
  * Note some platforms do not support getting and setting socket options on server sockets
  * so take care when using `getOption` and `setOption`.
  *
  * Client sockets can be accepted by pulling on the `accept` stream. A concurrent server
  * that is limited to `maxAccept` concurrent clients is accomplished by
  * `b.accept.map(handleClientSocket).parJoin(maxAccept)`.
  */
sealed trait ServerSocket[F[_]] extends SocketInfo[F] {

  def address: GenSocketAddress

  /** Stream of client sockets; typically processed concurrently to allow concurrent clients. */
  def accept: Stream[F, Socket[F]]
}

object ServerSocket {

  private[net] def apply[F[_]](
      info: SocketInfo[F],
      accept: Stream[F, Socket[F]]
  ): ServerSocket[F] = {
    val accept0 = accept
    new ServerSocket[F] {
      override def accept: Stream[F, Socket[F]] = accept0
      override def address = info.address
      override def getOption[A](key: SocketOption.Key[A]): F[Option[A]] = info.getOption(key)
      override def setOption[A](key: SocketOption.Key[A], value: A) = info.setOption(key, value)
      override def supportedOptions = info.supportedOptions
    }
  }
}
