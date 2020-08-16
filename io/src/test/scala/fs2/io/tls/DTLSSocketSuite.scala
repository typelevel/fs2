package fs2
package io
package tls

import scala.concurrent.duration._

import java.net.InetSocketAddress

import cats.effect.IO
import cats.implicits._

import fs2.io.udp.{Packet, SocketGroup}

class DTLSSocketSuite extends TLSSuite {
  group("DTLSSocket") {
    test("echo") {
      SocketGroup[IO].use { socketGroup =>
        testTlsContext.flatMap { tlsContext =>
          socketGroup.open[IO]().use { serverSocket =>
            serverSocket.localAddress.map(_.getPort).flatMap { serverPort =>
              val serverAddress = new InetSocketAddress("localhost", serverPort)
              socketGroup.open[IO]().use { clientSocket =>
                clientSocket.localAddress.map(_.getPort).flatMap { clientPort =>
                  val clientAddress = new InetSocketAddress("localhost", clientPort)
                  val serverLogger =
                    None // Some((msg: String) => IO(println(s"\u001b[33m${msg}\u001b[0m")))
                  val clientLogger =
                    None // Some((msg: String) => IO(println(s"\u001b[32m${msg}\u001b[0m")))
                  (
                    tlsContext.dtlsServer(serverSocket, clientAddress, logger = serverLogger),
                    tlsContext.dtlsClient(clientSocket, serverAddress, logger = clientLogger)
                  ).tupled.use {
                    case (dtlsServerSocket, dtlsClientSocket) =>
                      val echoServer =
                        dtlsServerSocket
                          .reads(None)
                          .foreach(p => dtlsServerSocket.write(p, None))
                      val msg = Chunk.bytes("Hello, world!".getBytes)
                      val echoClient = Stream.sleep_[IO](500.milliseconds) ++ Stream.exec(
                        dtlsClientSocket.write(Packet(serverAddress, msg))
                      ) ++ Stream.eval(dtlsClientSocket.read())
                      echoClient
                        .concurrently(echoServer)
                        .compile
                        .toList
                        .map(it => assert(it.map(_.bytes) == List(msg)))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
