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
          Zone { implicit z =>
            guard_ {
              s2n_cert_chain_and_key_load_pem_bytes(
                certChainAndKey,
                chainPem.toPtr,
                chainPem.length.toUInt,
                privateKeyPem.toPtr,
                privateKeyPem.length.toUInt
              )
            }
          }
        }
      }
}

object CertChainAndKey {
  def apply(chainPem: ByteVector, privateKeyPem: ByteVector): CertChainAndKey =
    new CertChainAndKey(chainPem, privateKeyPem)
}
