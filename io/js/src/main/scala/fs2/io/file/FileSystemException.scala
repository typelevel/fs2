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
package file

import scala.scalajs.js

class FileSystemException private[file] (cause: js.JavaScriptException) extends IOException(cause)
object FileSystemException {
  private[io] def unapply(cause: js.JavaScriptException): Option[FileSystemException] =
    FileAlreadyExistsException.unapply(cause).orElse(NoSuchFileException.unapply(cause))
}

class FileAlreadyExistsException private (cause: js.JavaScriptException)
    extends FileSystemException(cause)
object FileAlreadyExistsException {
  private[file] def unapply(cause: js.JavaScriptException): Option[FileAlreadyExistsException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("EEXIST") =>
        Some(new FileAlreadyExistsException(cause))
      case _ => None
    }
}

class NoSuchFileException private (cause: js.JavaScriptException) extends FileSystemException(cause)
object NoSuchFileException {
  private[file] def unapply(cause: js.JavaScriptException): Option[NoSuchFileException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ENOENT") =>
        Some(new NoSuchFileException(cause))
      case _ => None
    }
}
