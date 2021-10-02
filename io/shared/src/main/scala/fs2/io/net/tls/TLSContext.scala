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

import cats.effect.kernel.{Async, Resource}

/** Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext[F[_]] extends TLSContextPlatform[F] {

  /** Creates a `TLSSocket` builder in client mode. */
  def client(socket: Socket[F]): Resource[F, TLSSocket[F]] =
    clientBuilder(socket).build

  /** Creates a `TLSSocket` builder in client mode, allowing optional parameters to be configured.
    */
  def clientBuilder(socket: Socket[F]): TLSContext.SocketBuilder[F, TLSSocket]

  @deprecated("Use client(socket) or clientBuilder(socket).with(...).build", "3.0.6")
  def client(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]] =
    clientBuilder(socket).withParameters(params).withOldLogging(logger).build

  /** Creates a `TLSSocket` builder in server mode. */
  def server(socket: Socket[F]): Resource[F, TLSSocket[F]] =
    serverBuilder(socket).build

  /** Creates a `TLSSocket` builder in server mode, allowing optional parameters to be configured.
    */
  def serverBuilder(socket: Socket[F]): TLSContext.SocketBuilder[F, TLSSocket]

  @deprecated("Use server(socket) or serverBuilder(socket).with(...).build", "3.0.6")
  def server(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]] =
    serverBuilder(socket).withParameters(params).withOldLogging(logger).build

}

object TLSContext extends TLSContextCompanionPlatform {

  private[tls] trait UnsealedTLSContext[F[_]] extends TLSContext[F]

  trait Builder[F[_]] extends BuilderPlatform[F] {

    /** Creates a `TLSContext` from the system default `SSLContext`. */
    def system: F[TLSContext[F]]

  }

  object Builder extends BuilderCompanionPlatform {
    def forAsync[F[_]: Async]: Builder[F] = new AsyncBuilder
  }

  sealed trait SocketBuilder[F[_], S[_[_]]] {
    def withParameters(params: TLSParameters): SocketBuilder[F, S]
    def withLogging(log: (=> String) => F[Unit]): SocketBuilder[F, S]
    def withoutLogging: SocketBuilder[F, S]
    def withLogger(logger: TLSLogger[F]): SocketBuilder[F, S]
    private[tls] def withOldLogging(log: Option[String => F[Unit]]): SocketBuilder[F, S]
    def build: Resource[F, S[F]]
  }

  object SocketBuilder {
    private[tls] type Build[F[_], S[_[_]]] =
      (TLSParameters, TLSLogger[F]) => Resource[F, S[F]]

    private[tls] def apply[F[_], S[_[_]]](
        mkSocket: Build[F, S]
    ): SocketBuilder[F, S] =
      instance(mkSocket, TLSParameters.Default, TLSLogger.Disabled)

    private def instance[F[_], S[_[_]]](
        mkSocket: Build[F, S],
        params: TLSParameters,
        logger: TLSLogger[F]
    ): SocketBuilder[F, S] =
      new SocketBuilder[F, S] {
        def withParameters(params: TLSParameters): SocketBuilder[F, S] =
          instance(mkSocket, params, logger)
        def withLogging(log: (=> String) => F[Unit]): SocketBuilder[F, S] =
          withLogger(TLSLogger.Enabled(log))
        def withoutLogging: SocketBuilder[F, S] =
          withLogger(TLSLogger.Disabled)
        def withLogger(logger: TLSLogger[F]): SocketBuilder[F, S] =
          instance(mkSocket, params, logger)
        private[tls] def withOldLogging(
            log: Option[String => F[Unit]]
        ): SocketBuilder[F, S] =
          log.map(f => withLogging(m => f(m))).getOrElse(withoutLogging)
        def build: Resource[F, S[F]] =
          mkSocket(params, logger)
      }
  }
}
