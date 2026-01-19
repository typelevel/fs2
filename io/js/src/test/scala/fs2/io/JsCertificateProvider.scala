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
import fs2.io.file.Files
import scodec.bits.ByteVector
import scala.concurrent.duration._

/** Platform-specific implementation for JS platform using OpenSSL.
  */
private[io] object PlatformSpecificCertificateProvider {

  def create: IO[TestCertificateProvider] = IO.pure(new JsCertificateProvider)

  private class JsCertificateProvider extends TestCertificateProvider {

    def getCertificatePair: IO[TestCertificateProvider.CertificatePair] =
      Files[IO].tempDirectory.use { tempDir =>
        val certPath = tempDir / "cert.pem"
        val keyPath = tempDir / "key.pem"

        val cmd = List(
          "openssl",
          "req",
          "-x509",
          "-newkey",
          "rsa:2048",
          "-keyout",
          keyPath.toString,
          "-out",
          certPath.toString,
          "-days",
          "365",
          "-nodes",
          "-subj",
          "/CN=localhost/O=FS2 Tests",
          "-addext",
          "subjectAltName=DNS:localhost,IP:127.0.0.1",
          "-sha256"
        )

        def run(cmd: List[String]): IO[Unit] =
          fs2.io.process.ProcessBuilder(cmd.head, cmd.tail: _*).spawn[IO].use { p =>
            p.exitValue.flatMap {
              case 0 => IO.unit
              case n =>
                IO.raiseError(new RuntimeException(s"Command ${cmd.head} failed with exit code $n"))
            }
          }

        for {
          _ <- run(cmd)
          _ <- IO.sleep(1.second)
          cert <- Files[IO].readAll(certPath).compile.to(ByteVector)
          key <- Files[IO].readAll(keyPath).compile.to(ByteVector)
          certString <- Files[IO].readAll(certPath).through(fs2.text.utf8.decode).compile.string
          keyString <- Files[IO].readAll(keyPath).through(fs2.text.utf8.decode).compile.string
        } yield TestCertificateProvider.CertificatePair(
          certificate = cert,
          privateKey = key,
          certificateString = certString,
          privateKeyString = keyString
        )
      }
  }
}
