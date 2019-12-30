package fs2
package io
package tls

import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName

import fs2.io.tcp.SocketGroup

import cats.effect.{Blocker, Concurrent, ContextShift, IO}
import cats.implicits._

object TLSDebug {
  def debug[F[_]: Concurrent: ContextShift](
      blocker: Blocker,
      tlsContext: TLSContext,
      address: InetSocketAddress
  ): F[String] =
    SocketGroup[F](blocker).use { socketGroup =>
      socketGroup.client[F](address).use { rawSocket =>
        tlsContext
          .client(
            rawSocket,
            TLSParameters(serverNames = Some(List(new SNIHostName(address.getHostName))))
          )
          .use { tlsSocket =>
            tlsSocket.write(Chunk.empty) >>
              tlsSocket.session.map { session =>
                s"Cipher suite: ${session.getCipherSuite}\r\n" +
                  "Peer certificate chain:\r\n" + session.getPeerCertificates.zipWithIndex
                  .map { case (cert, idx) => s"Certificate $idx: $cert" }
                  .mkString("\r\n")
              }
          }
      }
    }
}

class TLSDebugTest extends Fs2Spec {

  def run(address: InetSocketAddress): IO[Unit] =
    Blocker[IO].use { blocker =>
      TLSDebug
        .debug[IO](blocker, TLSContext.system(blocker), address)
        .flatMap(l => IO(println(l)))
    }

  "google" in run(new InetSocketAddress("google.com", 443))
}
