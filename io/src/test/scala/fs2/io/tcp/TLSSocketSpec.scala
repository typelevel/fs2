package fs2
package io
package tcp

import scala.util.control.NonFatal

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

import cats.effect.{Blocker, IO}

import fs2.io.tls.TLSContext

class TLSSocketSpec extends Fs2Spec {
  "TLSSocket" - {
    "google" - {
      List("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").foreach { protocol =>
        protocol in {
          if (!supportedByPlatform(protocol)) {
            cancel(s"$protocol not supported by this platform")
          } else {
            Blocker[IO].use { blocker =>
              SocketGroup[IO](blocker).use { socketGroup =>
                socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { socket =>
                  TLSContext
                    .insecure[IO](blocker)
                    .engine(
                      enabledProtocols = Some(List(protocol))
                      // logger = Some(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
                    )
                    .flatMap { tlsEngine =>
                      TLSSocket(socket, tlsEngine)
                        .use { tlsSocket =>
                          (Stream("GET /\r\n\r\n")
                            .covary[IO]
                            .through(text.utf8Encode)
                            .through(tlsSocket.writes())
                            .drain ++
                            tlsSocket
                              .reads(8192)
                              .through(text.utf8Decode)
                              .through(text.lines)).head.compile.string
                        }
                        .asserting(_ shouldBe "HTTP/1.0 200 OK")
                    }
                }
              }
            }
          }
        }
      }
    }
  }

  def supportedByPlatform(protocol: String): Boolean =
    try {
      SSLContext.getInstance(protocol)
      true
    } catch {
      case NonFatal(_) => false
    }
}
