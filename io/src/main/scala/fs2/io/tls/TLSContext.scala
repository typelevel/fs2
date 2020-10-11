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
package tls

import scala.concurrent.duration._

import java.io.{FileInputStream, InputStream}
import java.net.InetSocketAddress
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
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._

import fs2.io.tcp.Socket
import fs2.io.udp.Packet
import java.util.function.BiFunction

/**
  * Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext {

  /**
    * Creates a `TLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def client[F[_]](
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  )(implicit F: Network[F]): Resource[F, TLSSocket[F]]

  /**
    * Creates a `TLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def server[F[_]](
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  )(implicit F: Network[F]): Resource[F, TLSSocket[F]]

  /**
    * Creates a `DTLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsClient[F[_]](
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  )(implicit F: Network[F]): Resource[F, DTLSSocket[F]]

  /**
    * Creates a `DTLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsServer[F[_]](
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  )(implicit F: Network[F]): Resource[F, DTLSSocket[F]]
}

object TLSContext {

  /** Creates a `TLSContext` from an `SSLContext`. */
  def fromSSLContext(
      ctx: SSLContext
  ): TLSContext =
    new TLSContext {
      def client[F[_]](
          socket: Socket[F],
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, TLSSocket[F]] =
        mkSocket(
          socket,
          true,
          params,
          logger
        )

      def server[F[_]](
          socket: Socket[F],
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, TLSSocket[F]] =
        mkSocket(
          socket,
          false,
          params,
          logger
        )

      private def mkSocket[F[_]](
          socket: Socket[F],
          clientMode: Boolean,
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, TLSSocket[F]] = {
        import F.async
        Resource
          .liftF(
            engine(
              new TLSEngine.Binding[F] {
                def write(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
                  socket.write(data, timeout)
                def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
                  socket.read(maxBytes, timeout)
              },
              clientMode,
              params,
              logger
            )
          )
          .flatMap(engine => TLSSocket(socket, engine))
      }

      def dtlsClient[F[_]](
          socket: udp.Socket[F],
          remoteAddress: InetSocketAddress,
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, DTLSSocket[F]] =
        mkDtlsSocket(
          socket,
          remoteAddress,
          true,
          params,
          logger
        )

      def dtlsServer[F[_]](
          socket: udp.Socket[F],
          remoteAddress: InetSocketAddress,
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, DTLSSocket[F]] =
        mkDtlsSocket(
          socket,
          remoteAddress,
          false,
          params,
          logger
        )

      private def mkDtlsSocket[F[_]](
          socket: udp.Socket[F],
          remoteAddress: InetSocketAddress,
          clientMode: Boolean,
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): Resource[F, DTLSSocket[F]] = {
        import F.async
        Resource
          .liftF(
            engine(
              new TLSEngine.Binding[F] {
                def write(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
                  if (data.isEmpty) Applicative[F].unit
                  else socket.write(Packet(remoteAddress, data), timeout)
                def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
                  socket.read(timeout).map(p => Some(p.bytes))
              },
              clientMode,
              params,
              logger
            )
          )
          .flatMap(engine => DTLSSocket(socket, remoteAddress, engine))
      }

      private def engine[F[_]](
          binding: TLSEngine.Binding[F],
          clientMode: Boolean,
          params: TLSParameters,
          logger: Option[String => F[Unit]]
      )(implicit F: Network[F]): F[TLSEngine[F]] = {
        import F.async
        val sslEngine = F.async.blocking {
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

  /** Creates a `TLSContext` which trusts all certificates. */
  def insecure[F[_]](implicit F: Network[F]): F[TLSContext] = {
    import F.async
    F.async
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
  }

  /** Creates a `TLSContext` from the system default `SSLContext`. */
  def system[F[_]](implicit F: Network[F]): F[TLSContext] = {
    import F.async
    F.async.blocking(SSLContext.getDefault).map(fromSSLContext(_))
  }

  /** Creates a `TLSContext` from the specified key store file. */
  def fromKeyStoreFile[F[_]](
      file: Path,
      storePassword: Array[Char],
      keyPassword: Array[Char]
  )(implicit F: Network[F]): F[TLSContext] = {
    import F.async
    val load = F.async.blocking(new FileInputStream(file.toFile): InputStream)
    val stream = Resource.make(load)(s => F.async.blocking(s.close))
    fromKeyStoreStream(stream, storePassword, keyPassword)
  }

  /** Creates a `TLSContext` from the specified class path resource. */
  def fromKeyStoreResource[F[_]](
      resource: String,
      storePassword: Array[Char],
      keyPassword: Array[Char]
  )(implicit F: Network[F]): F[TLSContext] = {
    import F.async
    val load = F.async.blocking(getClass.getClassLoader.getResourceAsStream(resource))
    val stream = Resource.make(load)(s => F.async.blocking(s.close))
    fromKeyStoreStream(stream, storePassword, keyPassword)
  }

  private def fromKeyStoreStream[F[_]](
      stream: Resource[F, InputStream],
      storePassword: Array[Char],
      keyPassword: Array[Char]
  )(implicit F: Network[F]): F[TLSContext] = {
    import F.async
    stream.use { s =>
      F.async
        .blocking {
          val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
          keyStore.load(s, storePassword)
          keyStore
        }
        .flatMap(fromKeyStore(_, keyPassword))
    }
  }

  /** Creates a `TLSContext` from the specified key store. */
  def fromKeyStore[F[_]](
      keyStore: KeyStore,
      keyPassword: Array[Char]
  )(implicit F: Network[F]): F[TLSContext] = {
    import F.async
    F.async
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
