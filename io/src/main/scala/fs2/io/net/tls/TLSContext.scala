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
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{
  KeyManagerFactory,
  SSLContext,
  SSLEngine,
  TrustManagerFactory,
  X509TrustManager
}

import cats.Applicative
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._

import com.comcast.ip4s.{IpAddress, SocketAddress}

import fs2.io.net.tcp.Socket
import fs2.io.net.udp.Packet
import java.util.function.BiFunction

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
      socket: udp.Socket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]

  /** Creates a `DTLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsServer(
      socket: udp.Socket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]
}

object TLSContext {

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

  object Builder {
    def forAsync[F[_]: Async]: Builder[F] = new AsyncBuilder

    /** Creates a `TLSContext` from an `SSLContext`. */
    private final class AsyncBuilder[F[_]: Async] extends Builder[F] {

      def fromSSLContext(
          ctx: SSLContext
      ): TLSContext[F] =
        new TLSContext[F] {
          def client(
              socket: Socket[F],
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, TLSSocket[F]] =
            mkSocket(
              socket,
              true,
              params,
              logger
            )

          def server(
              socket: Socket[F],
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, TLSSocket[F]] =
            mkSocket(
              socket,
              false,
              params,
              logger
            )

          private def mkSocket(
              socket: Socket[F],
              clientMode: Boolean,
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, TLSSocket[F]] =
            Resource
              .eval(
                engine(
                  new TLSEngine.Binding[F] {
                    def write(data: Chunk[Byte]): F[Unit] =
                      socket.write(data)
                    def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
                      socket.read(maxBytes)
                  },
                  clientMode,
                  params,
                  logger
                )
              )
              .flatMap(engine => TLSSocket(socket, engine))

          def dtlsClient(
              socket: udp.Socket[F],
              remoteAddress: SocketAddress[IpAddress],
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, DTLSSocket[F]] =
            mkDtlsSocket(
              socket,
              remoteAddress,
              true,
              params,
              logger
            )

          def dtlsServer(
              socket: udp.Socket[F],
              remoteAddress: SocketAddress[IpAddress],
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, DTLSSocket[F]] =
            mkDtlsSocket(
              socket,
              remoteAddress,
              false,
              params,
              logger
            )

          private def mkDtlsSocket(
              socket: udp.Socket[F],
              remoteAddress: SocketAddress[IpAddress],
              clientMode: Boolean,
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): Resource[F, DTLSSocket[F]] =
            Resource
              .eval(
                engine(
                  new TLSEngine.Binding[F] {
                    def write(data: Chunk[Byte]): F[Unit] =
                      if (data.isEmpty) Applicative[F].unit
                      else socket.write(Packet(remoteAddress, data))
                    def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
                      socket.read.map(p => Some(p.bytes))
                  },
                  clientMode,
                  params,
                  logger
                )
              )
              .flatMap(engine => DTLSSocket(socket, remoteAddress, engine))

          private def engine(
              binding: TLSEngine.Binding[F],
              clientMode: Boolean,
              params: TLSParameters,
              logger: Option[String => F[Unit]]
          ): F[TLSEngine[F]] = {
            val sslEngine = Async[F].blocking {
              val engine = ctx.createSSLEngine()
              engine.setUseClientMode(clientMode)
              engine.setSSLParameters(params.toSSLParameters)
              params.handshakeApplicationProtocolSelector
                .foreach { f =>
                  import fs2.io.CollectionCompat._
                  engine.setHandshakeApplicationProtocolSelector(
                    new BiFunction[SSLEngine, java.util.List[String], String] {
                      def apply(engine: SSLEngine, protocols: java.util.List[String]): String =
                        f(engine, protocols.asScala.toList)
                    }
                  )
                }
              engine
            }
            sslEngine.flatMap(TLSEngine[F](_, binding, logger))
          }
        }

      def insecure: F[TLSContext[F]] =
        Async[F]
          .blocking {
            val ctx = SSLContext.getInstance("TLS")
            val tm = new X509TrustManager {
              def checkClientTrusted(x: Array[X509Certificate], y: String): Unit = {}
              def checkServerTrusted(x: Array[X509Certificate], y: String): Unit = {}
              def getAcceptedIssuers(): Array[X509Certificate] = Array()
            }
            ctx.init(null, Array(tm), null)
            ctx
          }
          .map(fromSSLContext(_))

      def system: F[TLSContext[F]] =
        Async[F].blocking(SSLContext.getDefault).map(fromSSLContext(_))

      def fromKeyStoreFile(
          file: Path,
          storePassword: Array[Char],
          keyPassword: Array[Char]
      ): F[TLSContext[F]] = {
        val load = Async[F].blocking(new FileInputStream(file.toFile): InputStream)
        val stream = Resource.make(load)(s => Async[F].blocking(s.close))
        fromKeyStoreStream(stream, storePassword, keyPassword)
      }

      def fromKeyStoreResource(
          resource: String,
          storePassword: Array[Char],
          keyPassword: Array[Char]
      ): F[TLSContext[F]] = {
        val load = Async[F].blocking(getClass.getClassLoader.getResourceAsStream(resource))
        val stream = Resource.make(load)(s => Async[F].blocking(s.close))
        fromKeyStoreStream(stream, storePassword, keyPassword)
      }

      private def fromKeyStoreStream(
          stream: Resource[F, InputStream],
          storePassword: Array[Char],
          keyPassword: Array[Char]
      ): F[TLSContext[F]] =
        stream.use { s =>
          Async[F]
            .blocking {
              val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
              keyStore.load(s, storePassword)
              keyStore
            }
            .flatMap(fromKeyStore(_, keyPassword))
        }

      def fromKeyStore(
          keyStore: KeyStore,
          keyPassword: Array[Char]
      ): F[TLSContext[F]] =
        Async[F]
          .blocking {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
            kmf.init(keyStore, keyPassword)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
            tmf.init(keyStore)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
            sslContext
          }
          .map(fromSSLContext(_))
    }
  }
}
