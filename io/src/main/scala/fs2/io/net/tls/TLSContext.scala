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
  X509ExtendedTrustManager,
  X509TrustManager
}
import cats.Applicative
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}

import java.util.function.BiFunction

/** Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext[F[_]] {

  /** Creates a `TLSSocket` builder in client mode. */
  def client(socket: Socket[F]): Resource[F, TLSSocket[F]] =
    clientBuilder(socket).build

  /** Creates a `TLSSocket` builder in client mode, allowing optional parameters to be configured. */
  def clientBuilder(socket: Socket[F]): TLSContext.SocketBuilder[F, TLSSocket]

  @deprecated("Use client(socket) or clientBuilder(socket).with(...).build", "3.0.6")
  def client(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]] =
    clientBuilder(socket).
      withParameters(params).
      withOldLogging(logger).
      build

  /** Creates a `TLSSocket` builder in server mode. */
  def server(socket: Socket[F]): Resource[F, TLSSocket[F]] =
    serverBuilder(socket).build

  /** Creates a `TLSSocket` builder in server mode, allowing optional parameters to be configured. */
  def serverBuilder(socket: Socket[F]): TLSContext.SocketBuilder[F, TLSSocket]

  @deprecated("Use server(socket) or serverBuilder(socket).with(...).build", "3.0.6")
  def server(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]] =
    serverBuilder(socket).
      withParameters(params).
      withOldLogging(logger).
      build

  /** Creates a `DTLSSocket` builder in client mode. */
  def dtlsClient(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress]
  ): Resource[F, DTLSSocket[F]] =
    dtlsClientBuilder(socket, remoteAddress).build

  /** Creates a `DTLSSocket` builder in client mode, allowing optional parameters to be configured. */
  def dtlsClientBuilder(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress]
  ): TLSContext.SocketBuilder[F, DTLSSocket]

  @deprecated("Use dtlsClient(socket, remoteAddress) or dtlsClientBuilder(socket, remoteAddress).with(...).build", "3.0.6")
  def dtlsClient(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]] =
    dtlsClientBuilder(socket, remoteAddress).
      withParameters(params).
      withOldLogging(logger).
      build

  /** Creates a `DTLSSocket` builder in server mode. */
  def dtlsServer(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress]
  ): Resource[F, DTLSSocket[F]] =
    dtlsServerBuilder(socket, remoteAddress).build

  /** Creates a `DTLSSocket` builder in client mode, allowing optional parameters to be configured. */
  def dtlsServerBuilder(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress]
  ): TLSContext.SocketBuilder[F, DTLSSocket] 

  @deprecated("Use dtlsServer(socket, remoteAddress) or dtlsClientBuilder(socket, remoteAddress).with(...).build", "3.0.6")
  def dtlsServer(
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]] =
    dtlsServerBuilder(socket, remoteAddress).
      withParameters(params).
      withOldLogging(logger).
      build
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
          def clientBuilder(socket: Socket[F]) = SocketBuilder((p, l) => mkSocket(socket, true, p, l))

          def serverBuilder(socket: Socket[F]) = SocketBuilder((p, l) => mkSocket(socket, false, p, l))

          private def mkSocket(
              socket: Socket[F],
              clientMode: Boolean,
              params: TLSParameters,
              logger: TLSLogger[F]
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

          def dtlsClientBuilder(socket: DatagramSocket[F], remoteAddress: SocketAddress[IpAddress]) =
            SocketBuilder((p, l) => mkDtlsSocket(socket, remoteAddress, true, p, l))

          def dtlsServerBuilder(socket: DatagramSocket[F], remoteAddress: SocketAddress[IpAddress]) =
            SocketBuilder((p, l) => mkDtlsSocket(socket, remoteAddress, false, p, l))

          private def mkDtlsSocket(
              socket: DatagramSocket[F],
              remoteAddress: SocketAddress[IpAddress],
              clientMode: Boolean,
              params: TLSParameters,
              logger: TLSLogger[F]
          ): Resource[F, DTLSSocket[F]] =
            Resource
              .eval(
                engine(
                  new TLSEngine.Binding[F] {
                    def write(data: Chunk[Byte]): F[Unit] =
                      if (data.isEmpty) Applicative[F].unit
                      else socket.write(Datagram(remoteAddress, data))
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
              logger: TLSLogger[F]
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
            val tm = new X509ExtendedTrustManager {
              def checkClientTrusted(x: Array[X509Certificate], y: String): Unit = {}
              def checkServerTrusted(x: Array[X509Certificate], y: String): Unit = {}
              def getAcceptedIssuers(): Array[X509Certificate] = Array()

              override def checkClientTrusted(
                  chain: Array[X509Certificate],
                  authType: String,
                  socket: java.net.Socket
              ): Unit = {}

              override def checkServerTrusted(
                  chain: Array[X509Certificate],
                  authType: String,
                  socket: java.net.Socket
              ): Unit = {}

              override def checkClientTrusted(
                  chain: Array[X509Certificate],
                  authType: String,
                  engine: SSLEngine
              ): Unit = {}

              override def checkServerTrusted(
                  chain: Array[X509Certificate],
                  authType: String,
                  engine: SSLEngine
              ): Unit = {}
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

  sealed trait SocketBuilder[F[_], Socket[_[_]]] {
    def withParameters(params: TLSParameters): SocketBuilder[F, Socket]
    def withLogging(log: (=> String) => F[Unit]): SocketBuilder[F, Socket]
    def withoutLogging: SocketBuilder[F, Socket]
    def withLogger(logger: TLSLogger[F]): SocketBuilder[F, Socket]
    private[TLSContext] def withOldLogging(log: Option[String => F[Unit]]): SocketBuilder[F, Socket]
    def build: Resource[F, Socket[F]]
  }

  object SocketBuilder {
    private[TLSContext] type Build[F[_], Socket[_[_]]] = (TLSParameters, TLSLogger[F]) => Resource[F, Socket[F]]

    private[TLSContext] def apply[F[_], Socket[_[_]]](mkSocket: Build[F, Socket]): SocketBuilder[F, Socket] =
      instance(mkSocket, TLSParameters.Default, TLSLogger.Disabled)

    private def instance[F[_], Socket[_[_]]](
        mkSocket: Build[F, Socket],
        params: TLSParameters,
        logger: TLSLogger[F]
    ): SocketBuilder[F, Socket] =
      new SocketBuilder[F, Socket] {
        def withParameters(params: TLSParameters): SocketBuilder[F, Socket] =
          instance(mkSocket, params, logger)
        def withLogging(log: (=> String) => F[Unit]): SocketBuilder[F, Socket] =
          withLogger(TLSLogger.Enabled(log))
        def withoutLogging: SocketBuilder[F, Socket] =
          withLogger(TLSLogger.Disabled)
        def withLogger(logger: TLSLogger[F]): SocketBuilder[F, Socket] =
          instance(mkSocket, params, logger)
        private[TLSContext] def withOldLogging(log: Option[String => F[Unit]]): SocketBuilder[F, Socket] =
          log.map(f => withLogging(m => f(m))).getOrElse(withoutLogging)
        def build: Resource[F, Socket[F]] =
          mkSocket(params, logger)
      }
  }

}
