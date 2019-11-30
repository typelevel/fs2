package fs2
package io
package tls

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, X509TrustManager}

import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.implicits._

sealed trait TLSContext[F[_]] {
  def engine(
      clientMode: Boolean = true,
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      enableSessionCreation: Boolean = true,
      enabledCipherSuites: Option[List[String]] = None,
      enabledProtocols: Option[List[String]] = None
  ): F[TLSEngine[F]]
}

object TLSContext {
  def fromSSLContext[F[_]: Concurrent: ContextShift](
      ctx: SSLContext,
      blocker: Blocker
  ): TLSContext[F] = new TLSContext[F] {
    def engine(
        clientMode: Boolean,
        needClientAuth: Boolean,
        wantClientAuth: Boolean,
        enableSessionCreation: Boolean,
        enabledCipherSuites: Option[List[String]],
        enabledProtocols: Option[List[String]]
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
      sslEngine.flatMap(TLSEngine[F](_, blocker))
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
