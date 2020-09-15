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
package tls

import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName

import fs2.io.tcp.SocketGroup

import cats.effect.{Async, IO}
import cats.syntax.all._

object TLSDebug {
  def debug[F[_]: Async](
      tlsContext: TLSContext,
      address: InetSocketAddress
  ): F[String] =
    SocketGroup[F]().use { socketGroup =>
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

class TLSDebugTest extends Fs2Suite {

  def run(address: InetSocketAddress): IO[Unit] =
    TLSContext.system[IO].flatMap { ctx =>
      TLSDebug
        .debug[IO](ctx, address)
        .flatMap(l => IO(println(l)))
    }

  test("google")(run(new InetSocketAddress("google.com", 443)))
}
