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

package fs2.io.net.tls

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import scodec.bits.ByteVector

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import s2n._
import s2nutil._

final class CertChainAndKey private (chainPem: ByteVector, privateKeyPem: ByteVector) {
  private[tls] def toS2n[F[_]](implicit F: Sync[F]): Resource[F, Ptr[s2n_cert_chain_and_key]] =
    Resource
      .make(F.delay(guard(s2n_cert_chain_and_key_new())))(ccak =>
        F.delay(guard_(s2n_cert_chain_and_key_free(ccak)))
      )
      .evalTap { certChainAndKey =>
        F.delay {
          guard_ {
            s2n_cert_chain_and_key_load_pem_bytes(
              certChainAndKey,
              chainPem.toArray.atUnsafe(0),
              chainPem.length.toUInt,
              privateKeyPem.toArray.atUnsafe(0),
              privateKeyPem.length.toUInt
            )
          }
        }
      }
}

object CertChainAndKey {
  def apply(chainPem: ByteVector, privateKeyPem: ByteVector): CertChainAndKey =
    new CertChainAndKey(chainPem, privateKeyPem)
}
