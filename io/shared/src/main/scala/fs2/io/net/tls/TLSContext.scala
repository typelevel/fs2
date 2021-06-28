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
import com.comcast.ip4s.{IpAddress, SocketAddress}

/** Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext[F[_]] {

  /** Creates a `TLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def client(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  /** Creates a `TLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def server(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  /** Creates a `DTLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsClient(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]

  /** Creates a `DTLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsServer(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]
}

object TLSContext extends TLSContextPlatform {

  private[tls] trait UnsealedTLSContext[F[_]] extends TLSContext[F]

  trait Builder[F[_]] {
    def fromSSLContext(ctx: SSLContext): TLSContext[F]

    /** Creates a `TLSContext` which trusts all certificates. */
    def insecure: F[TLSContext[F]]

    /** Creates a `TLSContext` from the system default `SSLContext`. */
    def system: F[TLSContext[F]]

    /** Creates a `TLSContext` from the specified key store file. */
    def fromKeyStoreFile(
        file: Path,
        storePassword: Array[Char],
        keyPassword: Array[Char]
    ): F[TLSContext[F]]

    /** Creates a `TLSContext` from the specified class path resource. */
    def fromKeyStoreResource(
        resource: String,
        storePassword: Array[Char],
        keyPassword: Array[Char]
    ): F[TLSContext[F]]

    /** Creates a `TLSContext` from the specified key store. */
    def fromKeyStore(
        keyStore: KeyStore,
        keyPassword: Array[Char]
    ): F[TLSContext[F]]
  }

  object Builder extends BuilderPlatform {
    def forAsync[F[_]: Async]: Builder[F] = new AsyncBuilder
  }
}
