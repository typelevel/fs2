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
package process

sealed trait Process[F[_]] {

  def isAlive: F[Boolean]

  /** Fiber blocks until the process exits, then returns the exit value.
    * If the process has already exited then the exit value is available immediately.
    */
  def exitValue: F[Int]

  /** A `Pipe` that writes to `stdin` of the process. The resulting stream should be compiled
    * at most once, and interrupting or otherwise canceling a write-in-progress may kill the process.
    * If the process expects data through `stdin`, you have to supply it or close it. Failure to do so
    * may cause the process to block, or even deadlock.
    *
    * `stdin` resulting stream can be closed like this:
    *
    * @example {{{
    * Stream.empty.through(stdin).compile.drain
    * }}}
    */
  def stdin: Pipe[F, Byte, Nothing]

  /** A `Stream` that reads from `stdout` of the process. This stream should be compiled at most once,
    * and interrupting or otherwise canceling a read-in-progress may kill the process. Not draining
    * this `Stream` may cause the process to block, or even deadlock.
    */
  def stdout: Stream[F, Byte]

  /** A `Stream` that reads from `stderr` of the process. This stream should be compiled at most once,
    * and interrupting or otherwise canceling a read-in-progress may kill the process. Not draining
    * this `Stream` may cause the process to block, or even deadlock.
    */
  def stderr: Stream[F, Byte]

}

private[fs2] trait UnsealedProcess[F[_]] extends Process[F]
