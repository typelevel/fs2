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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration._

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

  private val cachedValue: AtomicReference[Option[CertificateAndPrivateKey]] =
    new AtomicReference(None)
  private val generating = new AtomicBoolean(false)

  /** Returns a cached certificate and private key, generating it if necessary.
    * The generation happens once per test suite execution using a self-signed certificate.
    */
  def getCertificateAndPrivateKey: IO[CertificateAndPrivateKey] =
    IO(cachedValue.get()).flatMap {
      case Some(v) => IO.pure(v)
      case None    =>
        IO(generating.compareAndSet(false, true)).flatMap { won =>
          if (won) {
            generateCertificateAndPrivateKey
              .flatMap { v =>
                IO(cachedValue.set(Some(v))).as(v)
              }
              .guarantee(IO(generating.set(false)))
          } else {
            IO.sleep(50.millis) >> getCertificateAndPrivateKey
          }
        }
    }

  private def generateCertificateAndPrivateKey: IO[CertificateAndPrivateKey] =
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
        "subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:Unknown",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=digitalSignature,keyEncipherment,keyCertSign",
        "-addext",
        "extendedKeyUsage=serverAuth,clientAuth",
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
