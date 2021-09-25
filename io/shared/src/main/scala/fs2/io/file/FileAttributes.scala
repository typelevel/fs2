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

import scala.concurrent.duration.FiniteDuration

/** Attributes of a file that are provided by most operating systems and file systems.
  *
  * To get an instance of `BasicFileAttributes`, use `Files[F].getBasicFileAttributes(path)`.
  *
  * The `fileKey` operation returns a unique identifier for the file, if the operating system and
  * file system supports providing such an identifier.
  */
sealed trait BasicFileAttributes {
  def creationTime: FiniteDuration
  def fileKey: Option[FileKey]
  def isDirectory: Boolean
  def isOther: Boolean
  def isRegularFile: Boolean
  def isSymbolicLink: Boolean
  def lastAccessTime: FiniteDuration
  def lastModifiedTime: FiniteDuration
  def size: Long
}

object BasicFileAttributes {
  private[file] trait UnsealedBasicFileAttributes extends BasicFileAttributes
}

// Note: we do not expose owner & group here since node.js doesn't provide easy
// access to owner/group names, only uid/gid. We could have alternatively read the
// `unix:uid` and `unix:gid` attributes and supplemented the result here, or made
// the owner/group operations JVM only.
sealed trait PosixFileAttributes extends BasicFileAttributes {
  def permissions: PosixPermissions
}

object PosixFileAttributes {
  private[file] trait UnsealedPosixFileAttributes extends PosixFileAttributes
}
