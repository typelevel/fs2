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

package fs2.io.internal

import org.typelevel.scalaccompat.annotation._

import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._

@nowarn212("cat=unused")
@extern
private[io] object syssocket {
  // only in Linux and FreeBSD, but not macOS
  final val SOCK_NONBLOCK = 2048

  // only on macOS and some BSDs (?)
  final val SO_NOSIGPIPE = 0x1022 /* APPLE: No SIGPIPE on EPIPE */

  def bind(sockfd: CInt, addr: Ptr[sockaddr], addrlen: socklen_t): CInt =
    extern

  def connect(sockfd: CInt, addr: Ptr[sockaddr], addrlen: socklen_t): CInt =
    extern

  def accept(sockfd: CInt, addr: Ptr[sockaddr], addrlen: Ptr[socklen_t]): CInt =
    extern

  // only supported on Linux and FreeBSD, but not macOS
  def accept4(sockfd: CInt, addr: Ptr[sockaddr], addrlen: Ptr[socklen_t], flags: CInt): CInt =
    extern
}
