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

import java.io.{FileInputStream, InputStream}
import cats.Applicative
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}

import java.util.function.BiFunction
import java.nio.file.Path

private[tls] trait TLSContextPlatform[F[_]]

private[tls] trait TLSContextCompanionPlatform { self: TLSContext.type =>

  private[tls] trait BuilderPlatform[F[_]] {
    def fromS2nConfig(ctx: S2nConfig): TLSContext[F]

    /** Creates a `TLSContext` which trusts all certificates. */
    def insecure: F[TLSContext[F]]
  }

  private[tls] trait BuilderCompanionPlatform {

    /** Creates a `TLSContext` from an `SSLContext`. */
    private[tls] final class AsyncBuilder[F[_]: Async] extends Builder[F] {
      def system: F[TLSContext[F]] = ???

      def systemResource: Resource[F, TLSContext[F]] = S2nConfig().map(fromS2nConfig(_))

      def insecure: F[TLSContext[F]] = ???

      def fromS2nConfig(cfg: S2nConfig): TLSContext[F] =
        new UnsealedTLSContext[F] {
          def clientBuilder(socket: Socket[F]) =
            SocketBuilder(mkSocket(socket, true, _, _))

          def serverBuilder(socket: Socket[F]) = ???

          private def mkSocket(
              socket: Socket[F],
              clientMode: Boolean,
              params: TLSParameters,
              logger: TLSLogger[F]
          ): Resource[F, TLSSocket[F]] =
            S2nConnection(socket, clientMode, cfg, params).flatMap(TLSSocket(socket, _))
        }
    }

  }

}
