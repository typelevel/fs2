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

import cats.effect.{IO, Ref, SyncIO}
import cats.effect.std.Mutex
import fs2.Stream
import fs2.io.file.{Files, Path}
import scodec.bits.ByteVector

object TestCertificateProvider {

  /** Represents a self-signed certificate and private key for testing.
    * Both values are PEM encoded.
    */
  case class CertificateAndPrivateKey(
      certificatePem: String,
      privateKeyPem: String
  )

  /** A leaf certificate issued by an intermediate CA, which is issued by a root CA.
    * All values are PEM encoded. The leaf is valid for `localhost` and `127.0.0.1`.
    */
  case class CertificateChain(
      rootPem: String,
      intermediatePem: String,
      leafPem: String,
      leafPrivateKeyPem: String
  )

  /** Decodes a single PEM certificate to DER. */
  def pemToDer(pem: String): ByteVector =
    ByteVector.fromValidBase64(
      pem.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "").replaceAll("\\s", "")
    )

  private val mutex: Mutex[IO] = Mutex.in[SyncIO, IO].unsafeRunSync()
  private val cache: Ref[IO, Option[CertificateAndPrivateKey]] = Ref.unsafe(None)
  private val chainCache: Ref[IO, Option[CertificateChain]] = Ref.unsafe(None)

  /** Returns a cached certificate and private key, generating it if necessary.
    * The generation happens once per test suite execution using a self-signed certificate.
    */
  def getCertificateAndPrivateKey: IO[CertificateAndPrivateKey] =
    mutex.lock.surround {
      cache.get.flatMap {
        case Some(c) => IO.pure(c)
        case None    => generateCertificateAndPrivateKey.flatTap(c => cache.set(Some(c)))
      }
    }

  /** Returns a cached certificate chain, generating it if necessary. */
  def getCertificateChain: IO[CertificateChain] =
    mutex.lock.surround {
      chainCache.get.flatMap {
        case Some(c) => IO.pure(c)
        case None    => generateCertificateChain.flatTap(c => chainCache.set(Some(c)))
      }
    }

  private def run(cmd: List[String]): IO[Unit] =
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

  private def writeFile(path: Path, contents: String): IO[Unit] =
    Stream(contents).through(Files[IO].writeUtf8(path)).compile.drain

  private def readFile(path: Path): IO[String] =
    Files[IO].readUtf8(path).compile.string

  private def generateCertificateChain: IO[CertificateChain] =
    Files[IO].tempDirectory.use { dir =>
      def p(name: String) = (dir / name).toString

      def newKeyAndCsr(name: String, cn: String) =
        run(
          List("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", s"/CN=$cn") ++
            List("-keyout", p(s"$name.key"), "-out", p(s"$name.csr"))
        )

      def sign(name: String, issuer: String, ext: String) =
        writeFile(dir / s"$name.ext", ext) >>
          run(
            List("openssl", "x509", "-req", "-in", p(s"$name.csr"), "-days", "365", "-sha256") ++
              List("-CA", p(s"$issuer.pem"), "-CAkey", p(s"$issuer.key"), "-CAcreateserial") ++
              List("-extfile", p(s"$name.ext"), "-out", p(s"$name.pem"))
          )

      val caExt = "basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n"
      val leafExt =
        "basicConstraints=critical,CA:FALSE\n" +
          "keyUsage=critical,digitalSignature,keyEncipherment\n" +
          "extendedKeyUsage=serverAuth,clientAuth\n" +
          "subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:Unknown\n"

      for {
        _ <- run(
          List("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365") ++
            List("-sha256", "-subj", "/CN=FS2 Test Root") ++
            List("-addext", "basicConstraints=critical,CA:TRUE") ++
            List("-addext", "keyUsage=critical,keyCertSign,cRLSign") ++
            List("-keyout", p("root.key"), "-out", p("root.pem"))
        )
        _ <- newKeyAndCsr("intermediate", "FS2 Test Intermediate")
        _ <- sign("intermediate", "root", caExt)
        _ <- newKeyAndCsr("leaf", "localhost")
        _ <- sign("leaf", "intermediate", leafExt)
        root <- readFile(dir / "root.pem")
        intermediate <- readFile(dir / "intermediate.pem")
        leaf <- readFile(dir / "leaf.pem")
        leafKey <- readFile(dir / "leaf.key")
      } yield CertificateChain(root, intermediate, leaf, leafKey)
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

      for {
        _ <- Stream(config).through(Files[IO].writeUtf8Lines(configPath)).compile.drain
        _ <- run(cmd)
        certString <- Files[IO].readUtf8(certPath).compile.string
        keyString <- Files[IO].readUtf8(keyPath).compile.string
      } yield CertificateAndPrivateKey(
        certString,
        keyString
      )
    }
}
