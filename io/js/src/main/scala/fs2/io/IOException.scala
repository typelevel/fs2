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

package fs2.io

import fs2.io.file.FileSystemException
import fs2.io.net.SocketException
import fs2.io.net.SocketTimeoutException
import fs2.io.net.UnknownHostException
import fs2.io.net.tls.SSLException

import scala.scalajs.js
import scala.util.control.NoStackTrace

private class JavaScriptIOException(message: String, cause: js.JavaScriptException)
    extends IOException(message, cause)
    with NoStackTrace

object IOException {
  private[io] def unapply(cause: js.JavaScriptException): Option[IOException] =
    InterruptedIOException
      .unapply(cause)
      .orElse(SocketException.unapply(cause))
      .orElse(SSLException.unapply(cause))
      .orElse(FileSystemException.unapply(cause))
      .orElse(UnknownHostException.unapply(cause))
      .orElse {
        cause.exception match {
          case error: js.Error if error.message.contains("EPIPE") =>
            Some(new JavaScriptIOException("Broken pipe", cause))
          case _ => None
        }
      }
}

class InterruptedIOException(message: String = null, cause: Throwable = null)
    extends IOException(message, cause)

object InterruptedIOException {
  private[io] def unapply(cause: js.JavaScriptException): Option[InterruptedIOException] =
    SocketTimeoutException.unapply(cause)
}

class ClosedChannelException extends IOException
