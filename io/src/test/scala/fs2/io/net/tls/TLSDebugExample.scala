/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package net
package tls

import javax.net.ssl.SNIHostName

import cats.effect.{Async, IO}
import cats.syntax.all._

import com.comcast.ip4s._

object TLSDebug {
  def debug[F[_]: Async](
      network: Network[F],
      tlsContext: TLSContext[F],
      host: SocketAddress[Hostname]
  ): F[String] =
    host.resolve.flatMap { socketAddress =>
      network.client(socketAddress).use { rawSocket =>
        tlsContext
          .client(
            rawSocket,
            TLSParameters(serverNames = Some(List(new SNIHostName(host.host.toString))))
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

class TLSDebugTest extends Fs2Suite {

  def run(address: SocketAddress[Hostname]): IO[Unit] = {
    val network = Network.create[IO]
    network.tlsContext.system.flatMap { ctx =>
      TLSDebug
        .debug[IO](network, ctx, address)
        .flatMap(l => IO(println(l)))
    }
  }

  test("google")(run(SocketAddress(host"google.com", port"443")))
}
