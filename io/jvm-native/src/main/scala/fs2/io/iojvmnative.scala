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

import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.reflect.ClassTag

private[fs2] trait iojvmnative {
  type InterruptedIOException = java.io.InterruptedIOException
  type ClosedChannelException = java.nio.channels.ClosedChannelException

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
