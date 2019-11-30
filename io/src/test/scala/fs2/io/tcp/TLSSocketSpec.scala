package fs2
package io
package tcp

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

import java.security.cert.X509Certificate

import cats.effect.{Blocker, Concurrent, ContextShift, IO, Sync}
import cats.implicits._

import fs2.io.tls.TLSEngine

class TLSSocketSpec extends Fs2Spec {
  def clientTlsEngine[F[_]: Concurrent: ContextShift](blocker: Blocker): F[TLSEngine[F]] = {
    val mkEngine = Sync[F].delay {
      val ctx = SSLContext.getInstance("TLS")
      import javax.net.ssl.X509TrustManager
      val tm = new X509TrustManager {
        def checkClientTrusted(x: Array[X509Certificate], y: String): Unit = {}
        def checkServerTrusted(x: Array[X509Certificate], y: String): Unit = {}
        def getAcceptedIssuers(): Array[X509Certificate] = Array()
      }
      ctx.init(null, Array(tm), null)
      val engine = ctx.createSSLEngine
      engine.setUseClientMode(true)
      engine.setNeedClientAuth(false)
      engine
    }
    mkEngine.flatMap(TLSEngine[F](_, blocker))
  }

  "TLSSocket" - {
    "google" in {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { socket =>
            clientTlsEngine[IO](blocker).flatMap { tlsEngine =>
              TLSSocket.instance(socket, tlsEngine).flatMap { tlsSocket =>
                (Stream("GET /\r\n\r\n")
                  .covary[IO]
                  .through(text.utf8Encode)
                  .through(tlsSocket.writes())
                  .drain ++
                  tlsSocket.reads(8192).through(text.utf8Decode))
                  .through(text.lines)
                  .head
                  .compile
                  .string
                  .asserting(_ shouldBe "HTTP/1.0 200 OK")
              }
            }
          }
        }
      }
    }
  }
}
