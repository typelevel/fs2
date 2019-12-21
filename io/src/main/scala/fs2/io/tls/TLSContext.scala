package fs2
package io
package tls

import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{
  KeyManagerFactory,
  SSLContext,
  TrustManagerFactory,
  X509TrustManager
}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

import fs2.io.tcp.Socket
import java.io.InputStream
import java.nio.file.Path
import java.io.FileInputStream

/**
  * Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext[F[_]] {

  /**
    * Creates a `TLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def client(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  /**
    * Creates a `TLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def server(
      socket: Socket[F],
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  /**
    * Creates a `DTLSSocket` in client mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsClient(
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]

  /**
    * Creates a `DTLSSocket` in server mode, using the supplied parameters.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def dtlsServer(
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      params: TLSParameters = TLSParameters.Default,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]
}

object TLSContext {

  /** Creates a `TLSContext` from an `SSLContext`. */
  def fromSSLContext[F[_]: Concurrent: ContextShift](
      ctx: SSLContext,
      blocker: Blocker
  ): TLSContext[F] = new TLSContext[F] {
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
        .liftF(
          engine(
            blocker,
            clientMode,
            params,
            logger
          )
        )
        .flatMap { engine =>
          TLSSocket(socket, engine)
        }

    def dtlsClient(
        socket: udp.Socket[F],
        remoteAddress: InetSocketAddress,
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
        remoteAddress: InetSocketAddress,
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
        remoteAddress: InetSocketAddress,
        clientMode: Boolean,
        params: TLSParameters,
        logger: Option[String => F[Unit]]
    ): Resource[F, DTLSSocket[F]] =
      Resource
        .liftF(
          engine(
            blocker,
            clientMode,
            params,
            logger
          )
        )
        .flatMap { engine =>
          DTLSSocket(socket, remoteAddress, engine)
        }

    private def engine(
        blocker: Blocker,
        clientMode: Boolean,
        params: TLSParameters,
        logger: Option[String => F[Unit]]
    ): F[TLSEngine[F]] = {
      val sslEngine = Sync[F].delay {
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(clientMode)
        engine.setSSLParameters(params.toSSLParameters)
        engine
      }
      sslEngine.flatMap(TLSEngine[F](_, blocker, logger))
    }
  }

  /** Creates a `TLSContext` which trusts all certificates. */
  def insecure[F[_]: Concurrent: ContextShift](blocker: Blocker): TLSContext[F] = {
    val ctx = SSLContext.getInstance("TLS")
    val tm = new X509TrustManager {
      def checkClientTrusted(x: Array[X509Certificate], y: String): Unit = {}
      def checkServerTrusted(x: Array[X509Certificate], y: String): Unit = {}
      def getAcceptedIssuers(): Array[X509Certificate] = Array()
    }
    ctx.init(null, Array(tm), null)
    fromSSLContext(ctx, blocker)
  }

  /** Creates a `TLSContext` from the system default `SSLContext`. */
  def system[F[_]: Concurrent: ContextShift](blocker: Blocker): TLSContext[F] =
    fromSSLContext(SSLContext.getDefault, blocker)

  /** Creates a `TLSContext` from the specified key store file. */
  def fromKeyStoreFile[F[_]: Concurrent: ContextShift](
      file: Path,
      storePassword: Array[Char],
      keyPassword: Array[Char],
      blocker: Blocker
  ): F[TLSContext[F]] = {
    val load = blocker.delay(new FileInputStream(file.toFile): InputStream)
    val stream = Resource.make(load)(s => blocker.delay(s.close))
    fromKeyStoreStream(stream, storePassword, keyPassword, blocker)
  }

  /** Creates a `TLSContext` from the specified class path resource. */
  def fromKeyStoreResource[F[_]: Concurrent: ContextShift](
      resource: String,
      storePassword: Array[Char],
      keyPassword: Array[Char],
      blocker: Blocker
  ): F[TLSContext[F]] = {
    val load = blocker.delay(getClass.getClassLoader.getResourceAsStream(resource))
    val stream = Resource.make(load)(s => blocker.delay(s.close))
    fromKeyStoreStream(stream, storePassword, keyPassword, blocker)
  }

  private def fromKeyStoreStream[F[_]: Concurrent: ContextShift](
      stream: Resource[F, InputStream],
      storePassword: Array[Char],
      keyPassword: Array[Char],
      blocker: Blocker
  ): F[TLSContext[F]] =
    stream.use { s =>
      blocker
        .delay {
          val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
          keyStore.load(s, storePassword)
          keyStore
        }
        .flatMap(fromKeyStore(_, keyPassword, blocker))
    }

  /** Creates a `TLSContext` from the specified key store. */
  def fromKeyStore[F[_]: Concurrent: ContextShift](
      keyStore: KeyStore,
      keyPassword: Array[Char],
      blocker: Blocker
  ): F[TLSContext[F]] =
    blocker
      .delay {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        kmf.init(keyStore, keyPassword)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        tmf.init(keyStore)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
        sslContext
      }
      .map(fromSSLContext(_, blocker))
}
