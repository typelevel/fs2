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

/** Options that customize a filesystem walk via `Files[F].walk`. */
sealed trait WalkOptions {

  /** Size of chunks emitted from the walk.
    *
    * Implementations *may* use this for optimization, batching file system operations.
    *
    * A chunk size of 1 hints to the implementation to use the maximally laziness in
    * file system access, emitting a single path at a time.
    *
    * A chunk size of `Int.MaxValue` hints to the implementation to perform all file system
    * operations at once, emitting a single chunk with all paths.
    */
  def chunkSize: Int

  /** Maximum depth to walk. A value of 0 results in emitting just the starting path.
    * A value of 1 results in emitting the starting path and all direct descendants.
    */
  def maxDepth: Int

  /** Indicates whether links are followed during the walk. If false, the path of
    * each link is emitted. If true, links are followed and their contents are emitted.
    */
  def followLinks: Boolean

  /** Indicates whether to allow cycles when following links. If true, any link causing a
    * cycle is emitted as the link path. If false, a cycle results in walk failing with a `FileSystemLoopException`.
    */
  def allowCycles: Boolean

  /** Returns a new `WalkOptions` with the specified chunk size. */
  def withChunkSize(chunkSize: Int): WalkOptions

  /** Returns a new `WalkOptions` with the specified max depth. */
  def withMaxDepth(maxDepth: Int): WalkOptions

  /** Returns a new `WalkOptions` with the specified value for `followLinks`. */
  def withFollowLinks(value: Boolean): WalkOptions

  /** Returns a new `WalkOptions` with the specified value for `allowCycles`. */
  def withAllowCycles(value: Boolean): WalkOptions
}

object WalkOptions {
  private case class DefaultWalkOptions(
      chunkSize: Int,
      maxDepth: Int,
      followLinks: Boolean,
      allowCycles: Boolean
  ) extends WalkOptions {
    def withChunkSize(chunkSize: Int): WalkOptions = copy(chunkSize = chunkSize)
    def withMaxDepth(maxDepth: Int): WalkOptions = copy(maxDepth = maxDepth)
    def withFollowLinks(value: Boolean): WalkOptions = copy(followLinks = value)
    def withAllowCycles(value: Boolean): WalkOptions = copy(allowCycles = value)
    override def toString =
      s"WalkOptions(chunkSize = $chunkSize, maxDepth = $maxDepth, followLinks = $followLinks, allowCycles = $allowCycles)"
  }

  /** Default walk options, using a large chunk size, unlimited depth, and no link following. */
  val Default: WalkOptions = DefaultWalkOptions(4096, Int.MaxValue, false, false)

  /** Like `Default` but uses the maximum chunk size, hinting the implementation should perform all file system operations before emitting any paths. */
  val Eager: WalkOptions = Default.withChunkSize(Int.MaxValue)

  /** Like `Default` but uses the minimum chunk size, hinting the implementation should perform minumum number of file system operations before emitting each path. */
  val Lazy: WalkOptions = Default.withChunkSize(1)
}
