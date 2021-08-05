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

import cats.kernel.Hash
import cats.Show

/**
 * An opaque, unique identifier for a file.
 * 
 * Paths which have the same `FileKey` (via univerals equals), 
 * as reported by `Files[F].getBasicFileAttributes(path).fileKey`,
 * are guaranteed to reference the same file on the file system.
 * 
 * Note: not all operating systems and file systems support file keys,
 * hence `BasicFileAttributes#fileKey` returns an `Option[FileKey]`.
 */
trait FileKey

object FileKey {
  implicit val hash: Hash[FileKey] = Hash.fromUniversalHashCode[FileKey]
  implicit val show: Show[FileKey] = Show.fromToString[FileKey]
}