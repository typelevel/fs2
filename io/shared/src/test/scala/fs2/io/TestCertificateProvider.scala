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

package fs2.io

import cats.effect.IO
import cats.effect.Ref
import scodec.bits.ByteVector

/** Platform-independent interface for test certificate generation.
  */
trait TestCertificateProvider {
  def getCertificatePair: IO[TestCertificateProvider.CertificatePair]
}

object TestCertificateProvider {

  /** Represents a certificate-key pair for testing
    */
  case class CertificatePair(
      certificate: ByteVector,
      privateKey: ByteVector,
      certificateString: String,
      privateKeyString: String
  )

  /** Creates a provider based on the current platform.
    * For JVM and JS: generates ephemeral certificates
    * For Native: falls back to file-based approach (files to be removed separately)
    */
  def createProvider: IO[TestCertificateProvider] =
    // This will be implemented differently for each platform
    PlatformSpecificCertificateProvider.create

  /** Cached certificate provider to avoid regenerating for each test
    */
  private lazy val cachedProvider: IO[TestCertificateProvider] =
    Ref.of[IO, Option[TestCertificateProvider]](None).flatMap { ref =>
      ref.get.flatMap {
        case Some(provider) => IO.pure(provider)
        case None           =>
          createProvider.flatTap(provider => ref.set(Some(provider)))
      }
    }

  /** Gets a cached certificate provider. This ensures we generate the certificate only once
    * per test suite execution, improving test performance.
    */
  def getCachedProvider: IO[TestCertificateProvider] = cachedProvider
}
