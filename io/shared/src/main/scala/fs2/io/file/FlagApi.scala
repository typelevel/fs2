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

package fs2.io.file

private[file] trait FlagCompanionApi {

  /** Open file for read access. */
  val Read: Flag

  /** Open file for write access. */
  val Write: Flag

  /** When combined with `Write`, writes are done at the end of the file. */
  val Append: Flag

  /** When combined with `Write`, truncates the file to 0 bytes when opening. */
  val Truncate: Flag

  /** Creates the file if it does not exist. */
  val Create: Flag

  /** Creates the file if it does not exist and fails if it already exists. */
  val CreateNew: Flag

  /** Requires all updates to the file content and metadata be written synchronously to underlying
    * storage.
    */
  val Sync: Flag

  /** Requires all updates to the file content be written synchronously to underlying storage. */
  val Dsync: Flag
}
