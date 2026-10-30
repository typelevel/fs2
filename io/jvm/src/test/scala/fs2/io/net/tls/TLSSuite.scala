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

import cats.effect.IO

import java.security.{KeyFactory, KeyStore, PrivateKey}
import java.security.cert.{Certificate, CertificateFactory}

abstract class TLSSuite extends Fs2Suite {
  def testTlsContext: IO[TLSContext[IO]] =
    TestCertificateProvider.getCertificateAndPrivateKey.flatMap { certPair =>
      val cert = parseCertificate(certPair.certificatePem)
      keyStoreTlsContext(
        Some((parsePrivateKey(certPair.privateKeyPem), List(cert))),
        trusted = List(cert)
      )
    }

  /** Trusts the test certificate but has no key, so it cannot present a certificate. */
  def testClientTlsContext: IO[TLSContext[IO]] =
    TestCertificateProvider.getCertificateAndPrivateKey.flatMap { certPair =>
      keyStoreTlsContext(None, trusted = List(parseCertificate(certPair.certificatePem)))
    }

  protected def parseCertificate(pem: String): Certificate =
    CertificateFactory
      .getInstance("X.509")
      .generateCertificate(
        new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      )

  protected def parsePrivateKey(pem: String): PrivateKey = {
    val keyBytes = java.util.Base64.getDecoder.decode(
      pem
        .replaceAll("-----BEGIN (.*)-----", "")
        .replaceAll("-----END (.*)-----", "")
        .replaceAll("\\s", "")
    )
    KeyFactory
      .getInstance("RSA")
      .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes))
  }

  /** A context presenting `identity` (a key and its certificate chain, leaf first), if any,
    * and trusting `trusted`.
    */
  protected def keyStoreTlsContext(
      identity: Option[(PrivateKey, List[Certificate])],
      trusted: List[Certificate]
  ): IO[TLSContext[IO]] =
    IO.blocking {
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
      keyStore.load(null, null)
      identity.foreach { case (key, chain) =>
        keyStore.setKeyEntry("alias", key, "password".toCharArray, chain.toArray)
      }
      trusted.zipWithIndex.foreach { case (cert, i) => keyStore.setCertificateEntry(s"ca$i", cert) }
      keyStore
    }.flatMap { ks =>
      Network[IO].tlsContext.fromKeyStore(ks, "password".toCharArray)
    }

  val logger = TLSLogger.Disabled
  // val logger = TLSLogger.Enabled(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
}
