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

import scala.scalajs.js
import scala.util.control.NoStackTrace

class SocketException(message: String = null, cause: Throwable = null)
    extends IOException(message, cause)
private class JavaScriptSocketException(cause: js.JavaScriptException)
    extends SocketException(cause = cause)
    with NoStackTrace
object SocketException {
  private[io] def unapply(cause: js.JavaScriptException): Option[SocketException] = cause match {
    case js.JavaScriptException(error: js.Error) if error.message.contains("ECONNRESET") =>
      Some(new JavaScriptSocketException(cause))
    case _ => BindException.unapply(cause).orElse(ConnectException.unapply(cause))
  }
}

class BindException(message: String = null, cause: Throwable = null)
    extends SocketException(message, cause)
private class JavaScriptBindException(cause: js.JavaScriptException)
    extends BindException(cause = cause)
    with NoStackTrace
object BindException {
  private[net] def unapply(cause: js.JavaScriptException): Option[BindException] = cause match {
    case js.JavaScriptException(error: js.Error) if error.message.contains("EADDRINUSE") =>
      Some(new JavaScriptBindException(cause))
    case _ => None
  }
}

class ConnectException(message: String = null, cause: Throwable = null)
    extends SocketException(message, cause)
private class JavaScriptConnectException(cause: js.JavaScriptException)
    extends ConnectException(cause = cause)
    with NoStackTrace
object ConnectException {
  private[net] def unapply(cause: js.JavaScriptException): Option[ConnectException] = cause match {
    case js.JavaScriptException(error: js.Error) if error.message.contains("ECONNREFUSED") =>
      Some(new JavaScriptConnectException(cause))
    case _ => None
  }
}

class SocketTimeoutException(message: String = null, cause: Throwable = null)
    extends IOException(message, cause)
private class JavaScriptSocketTimeoutException(cause: js.JavaScriptException)
    extends SocketTimeoutException(cause = cause)
    with NoStackTrace
object SocketTimeoutException {
  private[io] def unapply(cause: js.JavaScriptException): Option[SocketTimeoutException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ETIMEDOUT") =>
        Some(new JavaScriptSocketTimeoutException(cause))
      case _ => None
    }
}

class UnknownHostException(message: String = null, cause: Throwable = null)
    extends IOException(message, cause)
private class JavaScriptUnknownException(cause: js.JavaScriptException)
    extends UnknownHostException(cause = cause)
    with NoStackTrace
object UnknownHostException {
  private[io] def unapply(cause: js.JavaScriptException): Option[UnknownHostException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ENOTFOUND") =>
        Some(new JavaScriptUnknownException(cause))
      case _ => None
    }
}
