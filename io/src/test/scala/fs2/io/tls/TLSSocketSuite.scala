package fs2
package io
package tls

import scala.util.control.NonFatal
import scala.concurrent.duration._

import java.net.{InetAddress, InetSocketAddress}
import javax.net.ssl.{SNIHostName, SSLContext}

import cats.effect.IO

import fs2.io.tcp.SocketGroup

class TLSSocketSuite extends TLSSuite {
  group("TLSSocket") {
    group("google") {
      List("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").foreach { protocol =>
        if (!supportedByPlatform(protocol))
          test(s"$protocol - not supported by this platform".ignore) {}
        else
          test(protocol) {
            SocketGroup[IO]().use { socketGroup =>
              socketGroup.client[IO](new InetSocketAddress("www.google.com", 443)).use { socket =>
                TLSContext
                  .system[IO]
                  .flatMap { ctx =>
                    ctx
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
                      .map(it => assert(it == "HTTP/1.1 200 OK"))
                  }
              }
            }
          }
      }
    }

    test("echo") {
      SocketGroup[IO]().use { socketGroup =>
        testTlsContext.flatMap { tlsContext =>
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

                val msg = Chunk.bytes(("Hello, world! " * 20000).getBytes)
                val client =
                  Stream.resource(socketGroup.client[IO](serverAddress)).flatMap { clientSocket =>
                    Stream
                      .resource(
                        tlsContext.client(
                          clientSocket
                          // logger = Some((m: String) =>
                          //   IO.delay(println(s"${Console.MAGENTA}[TLS] $m${Console.RESET}"))
                          // )
                        )
                      )
                      .flatMap { clientSocketTls =>
                        Stream.eval_(clientSocketTls.write(msg)) ++
                          clientSocketTls.reads(8192).take(msg.size)
                      }
                  }

                client.concurrently(server).compile.to(Chunk).map(it => assert(it == msg))
            }
        }
      }
    }

    test("client reads before writing") {
      SocketGroup[IO]().use { socketGroup =>
        socketGroup.client[IO](new InetSocketAddress("google.com", 443)).use { rawSocket =>
          TLSContext.system[IO].flatMap { tlsContext =>
            tlsContext
              .client[IO](
                rawSocket,
                TLSParameters(
                  serverNames = Some(List(new SNIHostName("www.google.com")))
                )
                // logger = Some((m: String) =>
                //   IO.delay(println(s"${Console.MAGENTA}[TLS] $m${Console.RESET}"))
                // )
              )
              .use { socket =>
                val send = Stream("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
                  .through(text.utf8Encode)
                  .through(socket.writes())
                val receive = socket
                  .reads(8192)
                  .through(text.utf8Decode)
                  .through(text.lines)
                  .head
                receive
                  .concurrently(send.delayBy(100.millis))
                  .compile
                  .string
                  .map(it => assert(it == "HTTP/1.1 200 OK"))
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
