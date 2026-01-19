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

/** Platform-specific implementation for Native platform.
  */
private[io] object PlatformSpecificCertificateProvider {

  def create: IO[TestCertificateProvider] = IO.pure(new NativeCertificateProvider)

  private class NativeCertificateProvider extends TestCertificateProvider {

    def getCertificatePair: IO[TestCertificateProvider.CertificatePair] =

      Files[IO].tempDirectory.use { tempDir =>
        val caKey = tempDir / "ca_key.pem"
        val caCert = tempDir / "ca_cert.pem"
        val serverKey = tempDir / "server_key.pem"
        val serverCsr = tempDir / "server.csr"
        val serverCert = tempDir / "server_cert.pem"
        val configFile = tempDir / "openssl.cnf"

        val configContent =
          """
          |[req]
          |distinguished_name = req_distinguished_name
          |req_extensions = v3_req
          |x509_extensions = v3_ca
          |
          |[req_distinguished_name]
          |CN = Common Name
          |
          |[v3_ca]
          |basicConstraints = critical,CA:TRUE,pathlen:0
          |keyUsage = digitalSignature, keyCertSign, cRLSign
          |subjectKeyIdentifier = hash
          |authorityKeyIdentifier = keyid:always,issuer
          |
          |[v3_req]
          |basicConstraints = CA:FALSE
          |keyUsage = digitalSignature, keyEncipherment
          |extendedKeyUsage = serverAuth, clientAuth
          |subjectAltName = @alt_names
          |
          |[alt_names]
          |DNS.1 = localhost
          |DNS.2 = Unknown
          |IP.1 = 127.0.0.1
          |""".stripMargin

        val genCa = List(
          "openssl",
          "req",
          "-new",
          "-x509",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          caKey.toString,
          "-out",
          caCert.toString,
          "-days",
          "365",
          "-subj",
          "/CN=Test CA/O=FS2 Tests",
          "-config",
          configFile.toString,
          "-extensions",
          "v3_ca"
        )

        val genCsr = List(
          "openssl",
          "req",
          "-new",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          serverKey.toString,
          "-out",
          serverCsr.toString,
          "-subj",
          "/CN=Unknown/O=Unknown/OU=FS2 Tests/L=Unknown/ST=Unknown/C=XX",
          "-config",
          configFile.toString,
          "-extensions",
          "v3_req"
        )

        val signCert = List(
          "openssl",
          "x509",
          "-req",
          "-in",
          serverCsr.toString,
          "-CA",
          caCert.toString,
          "-CAkey",
          caKey.toString,
          "-CAcreateserial",
          "-out",
          serverCert.toString,
          "-days",
          "365",
          "-extfile",
          configFile.toString,
          "-extensions",
          "v3_req",
          "-sha256"
        )

        def run(cmd: List[String]): IO[Unit] =
          fs2.io.process.ProcessBuilder(cmd.head, cmd.tail: _*).spawn[IO].use { p =>
            for {
              out <- p.stdout.through(fs2.text.utf8.decode).compile.string
              err <- p.stderr.through(fs2.text.utf8.decode).compile.string
              exitCode <- p.exitValue
              _ <-
                if (exitCode == 0) IO.unit
                else
                  IO.raiseError(
                    new RuntimeException(
                      s"Command ${cmd.mkString(" ")} failed with exit code $exitCode\nStdout: $out\nStderr: $err"
                    )
                  )
            } yield ()
          }

        for {
          _ <- fs2
            .Stream(configContent)
            .through(fs2.text.utf8.encode)
            .through(Files[IO].writeAll(configFile))
            .compile
            .drain
          _ <- run(genCa)
          _ <- run(genCsr)
          _ <- run(signCert)
          _ <- IO.sleep(1.second)
          cert <- Files[IO].readAll(serverCert).compile.to(ByteVector)
          key <- Files[IO].readAll(serverKey).compile.to(ByteVector)
          caCertStr <- Files[IO].readAll(caCert).through(fs2.text.utf8.decode).compile.string
          keyString <- Files[IO].readAll(serverKey).through(fs2.text.utf8.decode).compile.string
        } yield TestCertificateProvider.CertificatePair(
          certificate = cert,
          privateKey = key,
          certificateString = caCertStr,
          privateKeyString = keyString
        )
      }
  }
}
