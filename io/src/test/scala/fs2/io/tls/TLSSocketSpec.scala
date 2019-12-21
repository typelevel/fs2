package fs2
package io
package tls

import scala.util.control.NonFatal

import java.net.InetSocketAddress
import javax.net.ssl.{SSLContext, SNIHostName}

import cats.effect.{Blocker, IO}

import fs2.io.tcp.SocketGroup
import java.net.InetAddress

class TLSSocketSpec extends TLSSpec {
  "TLSSocket" - {
    "google" - {
      List("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").foreach { protocol =>
        protocol in {
          if (!supportedByPlatform(protocol)) {
            cancel(s"$protocol not supported by this platform")
          } else {
            Blocker[IO].use { blocker =>
              SocketGroup[IO](blocker).use { socketGroup =>
                socketGroup.client[IO](new InetSocketAddress("www.google.com", 443)).use { socket =>
                  TLSContext
                    .system[IO](blocker)
                    .client(
                      socket,
                      TLSParameters(
                        protocols = Some(List(protocol)),
                        serverNames = Some(List(new SNIHostName("www.google.com")))
                      ),
                      logger = None // Some(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
                    )
                    .use { tlsSocket =>
                      (Stream("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
                        .covary[IO]
                        .through(text.utf8Encode)
                        .through(tlsSocket.writes())
                        .drain ++
                        tlsSocket
                          .reads(8192)
                          .through(text.utf8Decode)
                          .through(text.lines)).head.compile.string
                    }
                    .asserting(_ shouldBe "HTTP/1.1 200 OK")
                }
              }
            }
          }
        }
      }
    }

    "echo" in {
      Blocker[IO].use { blocker =>
        SocketGroup[IO](blocker).use { socketGroup =>
          testTlsContext(blocker).flatMap { tlsContext =>
            socketGroup
              .serverResource[IO](new InetSocketAddress(InetAddress.getByName(null), 0))
              .use {
                case (serverAddress, clients) =>
                  val server = clients.map { client =>
                    Stream.resource(client).flatMap { clientSocket =>
                      Stream.resource(tlsContext.server(clientSocket)).flatMap { clientSocketTls =>
                        clientSocketTls.reads(8192).chunks.flatMap { c =>
                          Stream.eval(clientSocketTls.write(c))
                        }
                      }
                    }
                  }.parJoinUnbounded

                  val msg = Chunk.bytes("Hello, world!".getBytes)
                  val client = Stream.resource(socketGroup.client[IO](serverAddress)).flatMap {
                    clientSocket =>
                      Stream.resource(tlsContext.client(clientSocket)).flatMap { clientSocketTls =>
                        Stream.eval_(clientSocketTls.write(msg)) ++
                          clientSocketTls.reads(8192).take(msg.size)
                      }
                  }

                  client.concurrently(server).compile.to(Chunk).asserting(_ shouldBe msg)
              }
          }
        }
      }
    }
  }

  private def supportedByPlatform(protocol: String): Boolean =
    try {
      SSLContext.getInstance(protocol)
      true
    } catch {
      case NonFatal(_) => false
    }
}
