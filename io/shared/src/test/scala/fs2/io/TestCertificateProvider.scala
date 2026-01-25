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
import cats.effect.std.AtomicCell
import cats.effect.unsafe.implicits.global
import fs2.Stream
import fs2.io.file.Files
import scodec.bits.ByteVector

object TestCertificateProvider {

  /** Represents a self-signed certificate and private key for testing.
    * Both values are PEM encoded.
    */
  case class CertificateAndPrivateKey(
      certificate: ByteVector,
      privateKey: ByteVector,
      certificateString: String,
      privateKeyString: String
  )

  private val cell: IO[AtomicCell[IO, Option[CertificateAndPrivateKey]]] = {
    val p = scala.concurrent.Promise[AtomicCell[IO, Option[CertificateAndPrivateKey]]]()
    AtomicCell[IO].of(Option.empty[CertificateAndPrivateKey]).unsafeRunAsync {
      case Right(c) => p.success(c)
      case Left(e)  => p.failure(e)
    }
    IO.fromFuture(IO(p.future))
  }

  /** Returns a cached certificate and private key, generating it if necessary.
    * The generation happens once per test suite execution using a self-signed certificate.
    */
  def getCertificateAndPrivateKey: IO[CertificateAndPrivateKey] =
    cell.flatMap {
      _.evalUpdateAndGet {
        case s @ Some(_) => IO.pure(s)
        case None        => generateCertificateAndPrivateKey.map(Some(_))
      }.map(_.get)
    }

  private def generateCertificateAndPrivateKey: IO[CertificateAndPrivateKey] =
    Files[IO].tempDirectory.use { tempDir =>
      val certPath = tempDir / "cert.pem"
      val keyPath = tempDir / "key.pem"
      val configPath = tempDir / "openssl.cnf"

      val config = """[req]
distinguished_name = req_distinguished_name
x509_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = localhost
O = FS2 Tests

[v3_req]
subjectAltName = DNS:localhost,IP:127.0.0.1,DNS:Unknown
basicConstraints = critical,CA:TRUE
keyUsage = digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth,clientAuth
"""

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
        "-config",
        configPath.toString,
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
        _ <- Stream(config).through(Files[IO].writeUtf8Lines(configPath)).compile.drain
        _ <- run(cmd)
        cert <- Files[IO].readAll(certPath).compile.to(ByteVector)
        key <- Files[IO].readAll(keyPath).compile.to(ByteVector)
        certString <- Files[IO].readAll(certPath).through(fs2.text.utf8.decode).compile.string
        keyString <- Files[IO].readAll(keyPath).through(fs2.text.utf8.decode).compile.string
      } yield CertificateAndPrivateKey(
        certificate = cert,
        privateKey = key,
        certificateString = certString,
        privateKeyString = keyString
      )
    }
}
