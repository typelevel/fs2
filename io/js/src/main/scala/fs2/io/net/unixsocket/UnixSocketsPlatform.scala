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
package unixsocket

import cats.effect.{Async, IO, LiftIO}
import fs2.io.file.Files

private[unixsocket] trait UnixSocketsCompanionPlatform { self: UnixSockets.type =>
  @deprecated("Use Network instead", "3.13.0")
  def forIO: UnixSockets[IO] = forLiftIO

  @deprecated("Use Network instead", "3.13.0")
  implicit def forLiftIO[F[_]: Async: LiftIO]: UnixSockets[F] = {
    val _ = LiftIO[F]
    forAsyncAndFiles
  }

  @deprecated("Use Network instead", "3.13.0")
  def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    forAsyncAndFiles(Files.forAsync(F), F)

  @deprecated("Use Network instead", "3.13.0")
  def forAsyncAndFiles[F[_]: Files](implicit F: Async[F]): UnixSockets[F] = {
    val _ = Files[F]
    new AsyncUnixSockets(new AsyncSocketsProvider)
  }
}
