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

import cats.effect.{Async, LiftIO, Resource}
import cats.syntax.all._
import com.comcast.ip4s.UnixSocketAddress
import fs2.io.file.{Files, Path}

private[net] trait UnixSocketsProviderCompanionPlatform {

  private[net] def forLiftIO[F[_]: Async: LiftIO]: UnixSocketsProvider[F] = {
    val _ = LiftIO[F]
    forAsync[F]
  }

  private[net] def forAsync[F[_]: Async]: UnixSocketsProvider[F] =
    forAsyncAndFiles(implicitly, Files.forAsync[F])

  private def forAsyncAndFiles[F[_]: Async: Files]: UnixSocketsProvider[F] =
    new AsyncSocketsProvider[F] with UnixSocketsProvider[F] {

      override def connect(
          address: UnixSocketAddress,
          options: List[SocketOption]
      ): Resource[F, Socket[F]] =
        connectIpOrUnix(Right(address), options)

      override def bind(
          address: UnixSocketAddress,
          options: List[SocketOption]
      ): Resource[F, ServerSocket[F]] = {

        var deleteIfExists: Boolean = false
        var deleteOnClose: Boolean = true

        val filteredOptions = options.filter { opt =>
          if (opt.key == SocketOption.UnixServerSocketDeleteIfExists) {
            deleteIfExists = opt.value.asInstanceOf[Boolean]
            false
          } else if (opt.key == SocketOption.UnixServerSocketDeleteOnClose) {
            deleteOnClose = opt.value.asInstanceOf[Boolean]
            false
          } else true
        }

        val delete = Resource.make(
          if (deleteIfExists) Files[F].deleteIfExists(Path(address.path)).void else Async[F].unit
        )(_ =>
          if (deleteOnClose) Files[F].deleteIfExists(Path(address.path)).void else Async[F].unit
        )

        delete *> bindIpOrUnix(Right(address), filteredOptions)
      }
    }
}
