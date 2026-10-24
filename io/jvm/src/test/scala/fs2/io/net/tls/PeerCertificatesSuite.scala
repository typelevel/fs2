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

import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import scodec.bits.ByteVector

import TestCertificateProvider.pemToDer

class PeerCertificatesSuite extends TLSSuite {

  private val testCertificate: IO[ByteVector] =
    TestCertificateProvider.getCertificateAndPrivateKey.map(c => pemToDer(c.certificatePem))

  /** Presents the generated leaf with its intermediate, and trusts only the root. */
  private val chainTlsContext: IO[TLSContext[IO]] =
    TestCertificateProvider.getCertificateChain.flatMap { chain =>
      keyStoreTlsContext(
        Some(
          (
            parsePrivateKey(chain.leafPrivateKeyPem),
            List(parseCertificate(chain.leafPem), parseCertificate(chain.intermediatePem))
          )
        ),
        trusted = List(parseCertificate(chain.rootPem))
      )
    }

  /** Completes a handshake and returns the peer certificates seen by (server, client). */
  private def peerCertificates(
      serverParams: TLSParameters,
      serverContext: Resource[IO, TLSContext[IO]],
      clientContext: Resource[IO, TLSContext[IO]]
  ): IO[(List[ByteVector], List[ByteVector])] =
    (for {
      serverContext <- serverContext
      clientContext <- clientContext
      serverSocket <- Network[IO].bind(SocketAddress(ip"127.0.0.1", Port.Wildcard))
      server = serverSocket.accept.head.compile.resource.lastOrError
        .flatMap(serverContext.serverBuilder(_).withParameters(serverParams).build)
        .evalTap(_.write(Chunk.empty)) // the JVM handshakes on first read or write
      client = Network[IO]
        .connect(serverSocket.address)
        .flatMap(clientContext.client(_))
        .evalTap(_.write(Chunk.empty))
      sockets <- (server, client).parTupled
    } yield sockets).use { case (server, client) =>
      (server.peerCertificates, client.peerCertificates).tupled
    }

  private val testContext = Resource.eval(testTlsContext)

  test("server sees the client certificate when client auth is required") {
    (
      testCertificate,
      peerCertificates(TLSParameters(needClientAuth = true), testContext, testContext)
    ).flatMapN { case (cert, (seenByServer, seenByClient)) =>
      IO {
        assertEquals(seenByServer, List(cert))
        assertEquals(seenByClient, List(cert))
      }
    }
  }

  test("server sees the client certificate when client auth is wanted") {
    (
      testCertificate,
      peerCertificates(TLSParameters(wantClientAuth = true), testContext, testContext)
    ).flatMapN { case (cert, (seenByServer, _)) => IO(assertEquals(seenByServer, List(cert))) }
  }

  test("empty when client auth is wanted but the client has no certificate") {
    (
      testCertificate,
      peerCertificates(
        TLSParameters(wantClientAuth = true),
        testContext,
        Resource.eval(testClientTlsContext)
      )
    ).flatMapN { case (cert, (seenByServer, seenByClient)) =>
      IO {
        assertEquals(seenByServer, Nil)
        assertEquals(seenByClient, List(cert))
      }
    }
  }

  test("empty when client auth is not requested") {
    peerCertificates(TLSParameters.Default, testContext, testContext).map {
      case (seenByServer, _) => assertEquals(seenByServer, Nil)
    }
  }

  test("insecure client sees the server certificate, as its trust manager accepts it") {
    (
      testCertificate,
      peerCertificates(TLSParameters.Default, testContext, Network[IO].tlsContext.insecureResource)
    ).flatMapN { case (cert, (_, seenByClient)) => IO(assertEquals(seenByClient, List(cert))) }
  }

  test("returns the chain as the peer sent it, leaf first, without the root") {
    val chainContext = Resource.eval(chainTlsContext)
    (
      TestCertificateProvider.getCertificateChain,
      peerCertificates(TLSParameters(needClientAuth = true), chainContext, chainContext)
    ).flatMapN { case (chain, (seenByServer, seenByClient)) =>
      val sent = List(pemToDer(chain.leafPem), pemToDer(chain.intermediatePem))
      IO {
        assertEquals(seenByServer, sent)
        assertEquals(seenByClient, sent)
      }
    }
  }
}
