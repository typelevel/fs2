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

import cats._
import cats.effect.kernel.Sync
import cats.effect.kernel.implicits._
import cats.syntax.all._

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.reflect.ClassTag

private[fs2] trait iojvmnative {
  type InterruptedIOException = java.io.InterruptedIOException
  type ClosedChannelException = java.nio.channels.ClosedChannelException

  //
  // STDIN/STDOUT Helpers

  /** Stream of bytes read asynchronously from standard input. */
  def stdin[F[_]: Sync](bufSize: Int): Stream[F, Byte] =
    readInputStream(Sync[F].blocking(System.in), bufSize, false)

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  def stdout[F[_]: Sync]: Pipe[F, Byte, Nothing] =
    writeOutputStream(Sync[F].blocking(System.out), false)

  /** Pipe of bytes that writes emitted values to standard error asynchronously. */
  def stderr[F[_]: Sync]: Pipe[F, Byte, Nothing] =
    writeOutputStream(Sync[F].blocking(System.err), false)

  /** Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    */
  def stdoutLines[F[_]: Sync, O: Show](
      charset: Charset = StandardCharsets.UTF_8
  ): Pipe[F, O, Nothing] =
    _.map(_.show).through(text.encode(charset)).through(stdout)

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  def stdinUtf8[F[_]: Sync](bufSize: Int): Stream[F, String] =
    stdin(bufSize).through(text.utf8.decode)

  /** Stream of bytes read asynchronously from the specified resource relative to the class `C`.
    * @see [[readClassLoaderResource]] for a resource relative to a classloader.
    */
  def readClassResource[F[_], C](
      name: String,
      chunkSize: Int = 64 * 1024
  )(implicit F: Sync[F], ct: ClassTag[C]): Stream[F, Byte] =
    Stream.eval(F.blocking(Option(ct.runtimeClass.getResourceAsStream(name)))).flatMap {
      case Some(resource) => io.readInputStream(resource.pure, chunkSize)
      case None           => Stream.raiseError(new IOException(s"Resource $name not found"))
    }

  /** Stream of bytes read asynchronously from the specified classloader resource.
    * @see [[readClassResource]] for a resource relative to a class.
    */
  def readClassLoaderResource[F[_]](
      name: String,
      chunkSize: Int = 64 * 1024,
      classLoader: ClassLoader = getClass().getClassLoader()
  )(implicit F: Sync[F]): Stream[F, Byte] =
    Stream.eval(F.blocking(Option(classLoader.getResourceAsStream(name)))).flatMap {
      case Some(resource) => io.readInputStream(resource.pure, chunkSize)
      case None           => Stream.raiseError(new IOException(s"Resource $name not found"))
    }

}
