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

import cats.effect.Sync

import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.libc.errno._
import scala.scalanative.posix.fcntl._
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.unsafe._

private[io] object NativeUtil {

  @alwaysinline def guard_(thunk: => CInt): Unit = {
    guard(thunk)
    ()
  }

  @alwaysinline def guard(thunk: => CInt): CInt =
    guardMask(thunk)(e => e == EAGAIN || e == EWOULDBLOCK)

  @alwaysinline def guardSSize(thunk: => CSSize): CSSize = {
    val rtn = thunk
    if (rtn < 0) {
      val e = errno
      if (e == EAGAIN || e == EWOULDBLOCK)
        rtn
      else throw errnoToThrowable(e)
    } else
      rtn
  }

  @alwaysinline def guardMask_(thunk: => CInt)(mask: Int => Boolean): Unit = {
    guardMask(thunk)(mask)
    ()
  }

  @alwaysinline def guardMask(thunk: => CInt)(mask: Int => Boolean): CInt = {
    val rtn = thunk
    if (rtn < 0) {
      val e = errno
      if (mask(e)) rtn
      else throw errnoToThrowable(e)
    } else
      rtn
  }

  @alwaysinline def errnoToThrowable(e: CInt): Throwable = {
    val msg = fromCString(strerror(e))
    if (e == EADDRINUSE /* || e == EADDRNOTAVAIL */ )
      new BindException(msg)
    else if (e == ECONNREFUSED)
      new ConnectException(msg)
    else
      new IOException(s"errno($e) $msg")
  }

  def setNonBlocking[F[_]](fd: CInt)(implicit F: Sync[F]): F[Unit] = F.delay {
    guard_(fcntl(fd, F_SETFL, O_NONBLOCK))
  }

}
