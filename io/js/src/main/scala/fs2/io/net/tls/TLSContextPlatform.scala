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
import fs2.io.internal.facade

import scala.scalajs.js

private[tls] trait TLSContextPlatform[F[_]]

private[tls] trait TLSContextCompanionPlatform { self: TLSContext.type =>

  private[tls] trait BuilderPlatform[F[_]] {
    def fromSecureContext(context: SecureContext): TLSContext[F]
    def system: F[TLSContext[F]]
    def insecure: F[TLSContext[F]]
  }

  private[tls] trait BuilderCompanionPlatform {
    private[tls] final class AsyncBuilder[F[_]](implicit F: Async[F]) extends UnsealedBuilder[F] {

      def fromSecureContext(
          context: SecureContext,
          insecure: Boolean
      ): TLSContext[F] =
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
          ): Resource[F, TLSSocket[F]] = (Dispatcher.sequential[F], Dispatcher.parallel[F])
            .flatMapN { (seqDispatcher, parDispatcher) =>
              if (clientMode) {
                Resource.eval(F.deferred[Either[Throwable, Unit]]).flatMap { handshake =>
                  TLSSocket
                    .forAsync(
                      socket,
                      sock => {
                        val options = params.toTLSConnectOptions(parDispatcher)
                        options.secureContext = context
                        if (insecure)
                          options.rejectUnauthorized = false
                        options.enableTrace = logger != TLSLogger.Disabled
                        options.socket = sock
                        val tlsSock = facade.tls.connect(options)
                        tlsSock.once(
                          "secureConnect",
                          () => seqDispatcher.unsafeRunAndForget(handshake.complete(Either.unit))
                        )
                        tlsSock.once[js.Error](
                          "error",
                          e =>
                            seqDispatcher.unsafeRunAndForget(
                              handshake.complete(Left(new js.JavaScriptException(e)))
                            )
                        )
                        tlsSock
                      }
                    )
                    .evalTap(_ => handshake.get.rethrow)
                }
              } else {
                Resource.eval(F.deferred[Either[Throwable, Unit]]).flatMap { verifyError =>
                  TLSSocket
                    .forAsync(
                      socket,
                      sock => {
                        val options = params.toTLSSocketOptions(parDispatcher)
                        options.secureContext = context
                        if (insecure)
                          options.rejectUnauthorized = false
                        options.enableTrace = logger != TLSLogger.Disabled
                        options.isServer = true
                        val tlsSock = new facade.tls.TLSSocket(sock, options)
                        tlsSock.once(
                          "secure",
                          { () =>
                            val requestCert = options.requestCert.getOrElse(false)
                            val rejectUnauthorized = options.rejectUnauthorized.getOrElse(true)
                            val result =
                              if (requestCert && rejectUnauthorized)
                                Option(tlsSock.ssl.verifyError())
                                  .map(e => new JavaScriptSSLException(js.JavaScriptException(e)))
                                  .toLeft(())
                              else Either.unit
                            seqDispatcher.unsafeRunAndForget(verifyError.complete(result))
                          }
                        )
                        tlsSock.once[js.Error](
                          "error",
                          e =>
                            seqDispatcher.unsafeRunAndForget(
                              verifyError.complete(Left(new js.JavaScriptException(e)))
                            )
                        )
                        tlsSock
                      }
                    )
                    .evalTap(_ => verifyError.get.rethrow)
                }
              }
            }
            .adaptError { case IOException(ex) => ex }
        }

      def fromSecureContext(context: SecureContext): TLSContext[F] =
        fromSecureContext(context, insecure = false)

      def system: F[TLSContext[F]] =
        Async[F].delay(fromSecureContext(SecureContext.default))

      def systemResource: Resource[F, TLSContext[F]] =
        Resource.eval(system)

      def insecure: F[TLSContext[F]] =
        Async[F].delay(fromSecureContext(SecureContext.default, insecure = true))

      def insecureResource: Resource[F, TLSContext[F]] =
        Resource.eval(insecure)
    }
  }
}
