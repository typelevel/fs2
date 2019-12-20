package fs2
package io
package tls

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, X509TrustManager}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

import fs2.io.tcp.Socket

sealed trait TLSContext[F[_]] {
  def client(
      socket: Socket[F],
      config: TLSSessionConfig = TLSSessionConfig(),
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]

  def server(
      socket: Socket[F],
      config: TLSSessionConfig = TLSSessionConfig(),
      logger: Option[String => F[Unit]] = None
  ): Resource[F, TLSSocket[F]]
}

object TLSContext {
  def fromSSLContext[F[_]: Concurrent: ContextShift](
      ctx: SSLContext,
      blocker: Blocker
  ): TLSContext[F] = new TLSContext[F] {
    def client(
        socket: Socket[F],
        config: TLSSessionConfig = TLSSessionConfig(),
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      mkSocket(socket, true, config, logger)

    def server(
        socket: Socket[F],
        config: TLSSessionConfig = TLSSessionConfig(),
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      mkSocket(socket, false, config, logger)

    private def mkSocket(
        socket: Socket[F],
        clientMode: Boolean,
        config: TLSSessionConfig,
        logger: Option[String => F[Unit]]
    ): Resource[F, TLSSocket[F]] =
      Resource
        .liftF(
          engine(
            blocker,
            clientMode,
            config,
            logger
          )
        )
        .flatMap { engine =>
          TLSSocket(socket, engine)
        }

    private def engine(
        blocker: Blocker,
        clientMode: Boolean,
        config: TLSSessionConfig,
        logger: Option[String => F[Unit]]
    ): F[TLSEngine[F]] = {
      val sslEngine = Sync[F].delay {
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(clientMode)
        engine.setNeedClientAuth(config.needClientAuth)
        engine.setWantClientAuth(config.wantClientAuth)
        engine.setEnableSessionCreation(config.enableSessionCreation)
        config.enabledCipherSuites.foreach(ecs => engine.setEnabledCipherSuites(ecs.toArray))
        config.enabledProtocols.foreach(ep => engine.setEnabledProtocols(ep.toArray))
        engine
      }
      sslEngine.flatMap(TLSEngine[F](_, blocker, logger))
    }
  }

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

  def system[F[_]: Concurrent: ContextShift](blocker: Blocker): TLSContext[F] =
    fromSSLContext(SSLContext.getDefault, blocker)
}
