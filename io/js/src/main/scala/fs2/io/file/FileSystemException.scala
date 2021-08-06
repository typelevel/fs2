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
import scala.util.control.NoStackTrace

class FileSystemException(message: String = null, cause: Throwable = null)
    extends IOException(message, cause)
object FileSystemException {
  private[io] def unapply(cause: js.JavaScriptException): Option[FileSystemException] =
    FileAlreadyExistsException
      .unapply(cause)
      .orElse(NoSuchFileException.unapply(cause))
      .orElse(NotDirectoryException.unapply(cause))
      .orElse(DirectoryNotEmptyException.unapply(cause))
      .orElse(AccessDeniedException.unapply(cause))
}

class AccessDeniedException(message: String = null, cause: Throwable = null)
    extends FileSystemException(message, cause)
private class JavaScriptAccessDeniedException(cause: js.JavaScriptException)
    extends AccessDeniedException(cause = cause)
    with NoStackTrace
object AccessDeniedException {
  private[file] def unapply(cause: js.JavaScriptException): Option[AccessDeniedException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("EACCES") =>
        Some(new JavaScriptAccessDeniedException(cause))
      case _ => None
    }
}

class DirectoryNotEmptyException(message: String = null, cause: Throwable = null)
    extends FileSystemException(message, cause)
private class JavaScriptDirectoryNotEmptyException(cause: js.JavaScriptException)
    extends DirectoryNotEmptyException(cause = cause)
    with NoStackTrace
object DirectoryNotEmptyException {
  private[file] def unapply(cause: js.JavaScriptException): Option[DirectoryNotEmptyException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ENOTEMPTY") =>
        Some(new JavaScriptDirectoryNotEmptyException(cause))
      case _ => None
    }
}

class FileAlreadyExistsException(message: String = null, cause: Throwable = null)
    extends FileSystemException(message, cause)
private class JavaScriptFileAlreadyExistsException(cause: js.JavaScriptException)
    extends FileAlreadyExistsException(cause = cause)
    with NoStackTrace
object FileAlreadyExistsException {
  private[file] def unapply(cause: js.JavaScriptException): Option[FileAlreadyExistsException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("EEXIST") =>
        Some(new JavaScriptFileAlreadyExistsException(cause))
      case _ => None
    }
}

class FileSystemLoopException(file: String) extends FileSystemException(file)

class NoSuchFileException(message: String = null, cause: Throwable = null)
    extends FileSystemException(message, cause)
private class JavaScriptNoSuchFileException(cause: js.JavaScriptException)
    extends NoSuchFileException(cause = cause)
    with NoStackTrace
object NoSuchFileException {
  private[file] def unapply(cause: js.JavaScriptException): Option[NoSuchFileException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ENOENT") =>
        Some(new JavaScriptNoSuchFileException(cause))
      case _ => None
    }
}

class NotDirectoryException(message: String = null, cause: Throwable = null)
    extends FileSystemException(message, cause)
private class JavaScriptNotDirectoryException(cause: js.JavaScriptException)
    extends NotDirectoryException(cause = cause)
    with NoStackTrace
object NotDirectoryException {
  private[file] def unapply(cause: js.JavaScriptException): Option[NotDirectoryException] =
    cause match {
      case js.JavaScriptException(error: js.Error) if error.message.contains("ENOTDIR") =>
        Some(new JavaScriptNotDirectoryException(cause))
      case _ => None
    }
}
