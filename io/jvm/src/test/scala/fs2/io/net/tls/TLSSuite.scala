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

abstract class TLSSuite extends Fs2Suite {
  def testTlsContext: IO[TLSContext[IO]] = TestCertificateProvider.getCachedProvider.flatMap {
    provider =>
      provider.getCertificatePair.flatMap { certPair =>
        IO.blocking {
          val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType)
          keyStore.load(null, null)

          val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
          val cert = certFactory.generateCertificate(
            new java.io.ByteArrayInputStream(certPair.certificate.toArray)
          )

          val keyFactory = java.security.KeyFactory.getInstance("RSA")
          val keyPem = certPair.privateKeyString
            .replaceAll("-----BEGIN (.*)-----", "")
            .replaceAll("-----END (.*)-----", "")
            .replaceAll("\\s", "")
          val keyBytes = java.util.Base64.getDecoder.decode(keyPem)
          val keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes)
          val key = keyFactory.generatePrivate(keySpec)

          keyStore.setKeyEntry("alias", key, "password".toCharArray, Array(cert))
          keyStore.setCertificateEntry("ca", cert)

          keyStore
        }.flatMap { ks =>
          Network[IO].tlsContext.fromKeyStore(ks, "password".toCharArray)
        }
      }
  }

  val logger = TLSLogger.Disabled
  // val logger = TLSLogger.Enabled(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
}
