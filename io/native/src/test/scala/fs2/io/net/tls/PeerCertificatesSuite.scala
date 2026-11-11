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
  private val chainContext: Resource[IO, TLSContext[IO]] = for {
    chain <- Resource.eval(TestCertificateProvider.getCertificateChain)
    cfg <- S2nConfig.builder
      .withCertChainAndKeysToStore(
        List(CertChainAndKey(chain.leafPem + chain.intermediatePem, chain.leafPrivateKeyPem))
      )
      .withPemsToTrustStore(List(chain.rootPem))
      .build[IO]
  } yield Network[IO].tlsContext.fromS2nConfig(cfg)

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
      client = Network[IO]
        .connect(serverSocket.address)
        .flatMap(
          clientContext
            .clientBuilder(_)
            .withParameters(TLSParameters(serverName = Some("Unknown")))
            .build
        )
      sockets <- (server, client).parTupled
    } yield sockets).use { case (server, client) =>
      (server.peerCertificates, client.peerCertificates).tupled
    }

  private val required = TLSParameters(clientAuthType = CertAuthType.Required.some)

  test("server sees the client certificate when client auth is required") {
    (testCertificate, peerCertificates(required, testTlsContext, testTlsContext)).flatMapN {
      case (cert, (seenByServer, seenByClient)) =>
        IO {
          // The self-signed certificate is its own trusted root, so it appears once
          assertEquals(seenByServer, List(cert))
          assertEquals(seenByClient, List(cert))
        }
    }
  }

  test("empty when client auth is optional and the client has no certificate") {
    peerCertificates(
      TLSParameters(clientAuthType = CertAuthType.Optional.some),
      testTlsContext,
      testClientTlsContext
    ).map { case (seenByServer, _) => assertEquals(seenByServer, Nil) }
  }

  test("empty when client auth is not requested") {
    peerCertificates(TLSParameters.Default, testTlsContext, testTlsContext).map {
      case (seenByServer, _) => assertEquals(seenByServer, Nil)
    }
  }

  test("insecure client sees no server certificate, as it was not verified") {
    peerCertificates(
      TLSParameters.Default,
      testTlsContext,
      Network[IO].tlsContext.insecureResource
    ).map { case (_, seenByClient) => assertEquals(seenByClient, Nil) }
  }

  test("returns the verified chain, leaf first, including the trusted root") {
    (
      TestCertificateProvider.getCertificateChain,
      peerCertificates(required, chainContext, chainContext)
    ).flatMapN { case (chain, (seenByServer, seenByClient)) =>
      val verified =
        List(chain.leafPem, chain.intermediatePem, chain.rootPem).map(pemToDer)
      IO {
        assertEquals(seenByServer, verified)
        assertEquals(seenByClient, verified)
      }
    }
  }
}
