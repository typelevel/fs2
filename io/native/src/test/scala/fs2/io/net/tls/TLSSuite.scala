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
import cats.effect.kernel.Resource
import fs2.io.file.Files
import fs2.io.file.Path
import scodec.bits.ByteVector

abstract class TLSSuite extends Fs2IoSuite {
  def testTlsContext: Resource[IO, TLSContext[IO]] = for {
    cert <- Resource.eval {
      Files[IO].readAll(Path("io/shared/src/test/resources/cert.pem")).compile.to(ByteVector)
    }
    key <- Resource.eval {
      Files[IO].readAll(Path("io/shared/src/test/resources/key.pem")).compile.to(ByteVector)
    }
    cfg <- S2nConfig.builder
      .withCertChainAndKeysToStore(List(CertChainAndKey(cert, key)))
      .withPemsToTrustStore(List(cert.decodeAscii.toOption.get))
      .build[IO]
  } yield Network[IO].tlsContext.fromS2nConfig(cfg)

  def testClientTlsContext: Resource[IO, TLSContext[IO]] = for {
    cert <- Resource.eval {
      Files[IO].readAll(Path("io/shared/src/test/resources/cert.pem")).compile.to(ByteVector)
    }
    cfg <- S2nConfig.builder
      .withPemsToTrustStore(List(cert.decodeAscii.toOption.get))
      .build[IO]
  } yield Network[IO].tlsContext.fromS2nConfig(cfg)

  val logger = TLSLogger.Disabled
  // val logger = TLSLogger.Enabled(msg => IO(println(s"\u001b[33m${msg}\u001b[0m")))
}
