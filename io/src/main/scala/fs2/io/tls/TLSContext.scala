package fs2
package io
package tls

import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, X509TrustManager}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

import fs2.io.tcp.Socket

/**
  * Allows creation of [[TLSSocket]]s.
  */
sealed trait TLSContext[F[_]] {

  /**
    * Creates a `TLSSocket` in client mode, using the supplied configuration.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def client(
      socket: Socket[F],
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      enableSessionCreation: Boolean = true,
      enabledCipherSuites: Option[List[String]] = None,
      enabledProtocols: Option[List[String]] = None,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  /**
    * Creates a `TLSSocket` in server mode, using the supplied configuration.
    * Internal debug logging of the session can be enabled by passing a logger.
    */
  def server(
      socket: Socket[F],
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      enableSessionCreation: Boolean = true,
      enabledCipherSuites: Option[List[String]] = None,
      enabledProtocols: Option[List[String]] = None,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  def dtlsClient(
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      enableSessionCreation: Boolean = true,
      enabledCipherSuites: Option[List[String]] = None,
      enabledProtocols: Option[List[String]] = None,
      logger: Option[String => F[Unit]] = None
  ): Resource[F, DTLSSocket[F]]

  def dtlsServer(
      socket: udp.Socket[F],
      remoteAddress: InetSocketAddress,
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      enableSessionCreation: Boolean = true,
      enabledCipherSuites: Option[List[String]] = None,
      enabledProtocols: Option[List[String]] = None,
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
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      mkSocket(
        socket,
        true,
        needClientAuth,
        wantClientAuth,
        enableSessionCreation,
        enabledCipherSuites,
        enabledProtocols,
        logger
      )

    def server(
        socket: Socket[F],
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      mkSocket(
        socket,
        false,
        needClientAuth,
        wantClientAuth,
        enableSessionCreation,
        enabledCipherSuites,
        enabledProtocols,
        logger
      )

    private def mkSocket(
        socket: Socket[F],
        clientMode: Boolean,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      Resource
        .liftF(
          engine(
            blocker,
            clientMode,
            needClientAuth,
            wantClientAuth,
            enableSessionCreation,
            enabledCipherSuites,
            enabledProtocols,
            logger
          )
        )
        .flatMap { engine =>
          TLSSocket(socket, engine)
        }

    def dtlsClient(
        socket: udp.Socket[F],
        remoteAddress: InetSocketAddress,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, DTLSSocket[F]] =
      mkDtlsSocket(
        socket,
        remoteAddress,
        true,
        needClientAuth,
        wantClientAuth,
        enableSessionCreation,
        enabledCipherSuites,
        enabledProtocols,
        logger
      )

    def dtlsServer(
        socket: udp.Socket[F],
        remoteAddress: InetSocketAddress,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, DTLSSocket[F]] =
      mkDtlsSocket(
        socket,
        remoteAddress,
        false,
        needClientAuth,
        wantClientAuth,
        enableSessionCreation,
        enabledCipherSuites,
        enabledProtocols,
        logger
      )

    private def mkDtlsSocket(
        socket: udp.Socket[F],
        remoteAddress: InetSocketAddress,
        clientMode: Boolean,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): Resource[F, DTLSSocket[F]] =
      Resource
        .liftF(
          engine(
            blocker,
            clientMode,
            needClientAuth,
            wantClientAuth,
            enableSessionCreation,
            enabledCipherSuites,
            enabledProtocols,
            logger
          )
        )
        .flatMap { engine =>
          DTLSSocket(socket, remoteAddress, engine)
        }

    private def engine(
        blocker: Blocker,
        clientMode: Boolean,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]],
        logger: Option[String => F[Unit]]
    ): F[TLSEngine[F]] = {
      val sslEngine = Sync[F].delay {
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(clientMode)
        engine.setNeedClientAuth(needClientAuth)
        engine.setWantClientAuth(wantClientAuth)
        engine.setEnableSessionCreation(enableSessionCreation)
        enabledCipherSuites.foreach(ecs => engine.setEnabledCipherSuites(ecs.toArray))
        enabledProtocols.foreach(ep => engine.setEnabledProtocols(ep.toArray))
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
}
