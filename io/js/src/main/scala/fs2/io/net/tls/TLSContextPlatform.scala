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
package tls

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.syntax.all._
import fs2.internal.jsdeps.node.nodeNetMod
import fs2.internal.jsdeps.node.tlsMod

private[tls] trait TLSContextPlatform[F[_]]

private[tls] trait TLSContextCompanionPlatform { self: TLSContext.type =>

  private[tls] trait BuilderPlatform[F[_]] {
    def fromSecureContext(context: SecureContext): TLSContext[F]
  }

  private[tls] trait BuilderCompanionPlatform {
    private[tls] final class AsyncBuilder[F[_]](implicit F: Async[F]) extends Builder[F] {

      def fromSecureContext(context: SecureContext): TLSContext[F] =
        new UnsealedTLSContext[F] {

          override def clientBuilder(socket: Socket[F]): SocketBuilder[F, TLSSocket] =
            SocketBuilder(mkSocket(socket, true, _, _))

          override def serverBuilder(socket: Socket[F]): SocketBuilder[F, TLSSocket] =
            SocketBuilder(mkSocket(socket, false, _, _))

          private def mkSocket(
              socket: Socket[F],
              clientMode: Boolean,
              params: TLSParameters,
              logger: TLSLogger[F]
          ): Resource[F, TLSSocket[F]] = Dispatcher[F]
            .flatMap { dispatcher =>
              import SecureContext.ops

              if (clientMode) {
                val options = params
                  .toConnectionOptions(dispatcher)
                  .setSecureContext(context.toJS)
                  .setEnableTrace(logger != TLSLogger.Disabled)
                TLSSocket.forAsync(
                  socket,
                  sock => tlsMod.connect(options.setSocket(sock.asInstanceOf[nodeNetMod.Socket]))
                )
              } else {
                val options = params
                  .toTLSSocketOptions(dispatcher)
                  .setSecureContext(context.toJS)
                  .setEnableTrace(logger != TLSLogger.Disabled)
                  .setIsServer(true)
                TLSSocket.forAsync(
                  socket,
                  sock => new tlsMod.TLSSocket(sock.asInstanceOf[nodeNetMod.Socket], options)
                )
              }
            }
            .adaptError { case IOException(ex) => ex }
        }

      def system: F[TLSContext[F]] = Async[F].delay(fromSecureContext(SecureContext.default))
    }
  }
}
