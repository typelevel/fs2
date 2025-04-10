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

private[net] trait ServerSocketCompanionPlatform {
  private[net] def apply[F[_]](info: SocketInfo[F], accept: Stream[F, Socket[F]]): ServerSocket[F] = {
    val accept0 = accept
    new UnsealedServerSocket[F] {
      def accept: Stream[F, Socket[F]] = accept0

      def getOption[A](key: SocketOption.Key[A]): F[Option[A]] = info.getOption(key)
      def setOption[A](key: SocketOption.Key[A], value: A) = info.setOption(key, value)
      def supportedOptions = info.supportedOptions

      def localAddress = info.localAddress
      def localAddressGen = info.localAddressGen
    }
  }
}

