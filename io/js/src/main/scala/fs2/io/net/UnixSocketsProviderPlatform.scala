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

import cats.effect.{Async, IO, LiftIO, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.comcast.ip4s.UnixSocketAddress
import fs2.concurrent.Channel
import fs2.io.file.{Files, Path}
import fs2.io.internal.facade

import scala.scalajs.js

private[net] trait UnixSocketsProviderCompanionPlatform {
  def forIO: UnixSocketsProvider[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: UnixSocketsProvider[F] = {
    val _ = LiftIO[F]
    forAsyncAndFiles
  }

  def forAsync[F[_]](implicit F: Async[F]): UnixSocketsProvider[F] =
    forAsyncAndFiles(Files.forAsync(F), F)

  def forAsyncAndFiles[F[_]: Files](implicit F: Async[F]): UnixSocketsProvider[F] =
    new UnixSocketsProvider[F] {

      private def setSocketOptions(options: List[SocketOption])(socket: facade.net.Socket): F[Unit] =
        options.traverse_(option => option.key.set(socket, option.value))

      override def connect(address: UnixSocketAddress, options: List[SocketOption]): Resource[F, Socket[F]] =
        (Resource
          .make(
            F.delay(
              new facade.net.Socket(new facade.net.SocketOptions { allowHalfOpen = true })
            )
          )(socket =>
            F.delay {
              if (!socket.destroyed)
                socket.destroy()
            }
          )
          .evalTap(setSocketOptions(options))
          .evalTap { socket =>
            F.async[Unit] { cb =>
              socket
                .registerOneTimeListener[F, js.Error]("error") { error =>
                  cb(Left(js.JavaScriptException(error)))
                } <* F.delay {
                  socket.connect(address.path, () => cb(Right(())))
                }
            }
          }
          .flatMap(Socket.forAsync[F])).adaptError { case IOException(ex) => ex }

      override def bind(
          address: UnixSocketAddress,
          options: List[SocketOption]
      ): Resource[F, ServerSocket[F]] = {

        var deleteIfExists: Boolean = false
        var deleteOnClose: Boolean = true

        // TODO use options
        val filteredOptions = options.filter { opt =>
          if (opt.key == SocketOption.UnixServerSocketDeleteIfExists) {
            deleteIfExists = opt.value.asInstanceOf[Boolean]
            false
          } else if (opt.key == SocketOption.UnixServerSocketDeleteOnClose) {
            deleteOnClose = opt.value.asInstanceOf[Boolean]
            false
          } else true
        }

        for {
          dispatcher <- Dispatcher.sequential[F]
          channel <- Resource.eval(Channel.unbounded[F, facade.net.Socket])
          server <- Resource.make(
            F.delay {
              facade.net.createServer(
                new facade.net.ServerOptions {
                  pauseOnConnect = true
                  allowHalfOpen = true
                },
                sock => dispatcher.unsafeRunAndForget(channel.send(sock))
              )
            }
          )(server =>
            F.async_[Unit] { cb =>
              if (server.listening) {
                server.close(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))
                ()
              } else
                cb(Right(()))
            }
          )

          _ <- Resource.make(
            if (deleteIfExists) Files[F].deleteIfExists(Path(address.path)).void else F.unit
          )(_ => if (deleteOnClose) Files[F].deleteIfExists(Path(address.path)).void else F.unit)

          _ <- Resource.eval(
            F.async[Unit] { cb =>
              server.registerOneTimeListener[F, js.Error]("error") { e =>
                cb(Left(js.JavaScriptException(e)))
              } <* F.delay(server.listen(address.path, () => cb(Right(()))))
            }
          )

          info = new SocketInfo[F] {
            def localAddressGen = F.delay { UnixSocketAddress(address.path) }
            def getOption[A](key: SocketOption.Key[A]) =
              F.raiseError(new UnsupportedOperationException)
            def setOption[A](key: SocketOption.Key[A], value: A) =
              F.raiseError(new UnsupportedOperationException)
            def supportedOptions =
              F.raiseError(new UnsupportedOperationException)
          }
          accept = channel.stream
            .evalTap(setSocketOptions(filteredOptions))
            .flatMap(sock => Stream.resource(Socket.forAsync(sock)))
        } yield ServerSocket(info, accept)
      }

    }

}
