package fs2
package io
package tcp

import java.net.InetSocketAddress

import cats.effect.{Blocker, IO}

import fs2.io.tls.TLSContext

class TLSSocketSpec extends Fs2Spec {
  "TLSSocket" - {
    "google" in {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { socket =>
            TLSContext.insecure[IO](blocker).engine().flatMap { tlsEngine =>
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
