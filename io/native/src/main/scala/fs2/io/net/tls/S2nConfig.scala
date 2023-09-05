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

import cats.effect.SyncIO
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.util.control.NonFatal

import s2n._
import s2nutil._

/** An `s2n_config`.
  *
  * See [[https://github.com/aws/s2n-tls/ s2n-tls]] for detailed documentation.
  */
final class S2nConfig private (private[tls] val ptr: Ptr[s2n_config])

object S2nConfig {

  // we must call this *exactly* once, before doing anything else
  // since you need an S2nConfig before you can do anything, this seems like the right place
  s2n_init()

  def builder: Builder = new BuilderImpl

  sealed abstract class Builder {
    def build[F[_]: Sync]: Resource[F, S2nConfig]

    def withCertChainAndKeysToStore(certKeyPairs: List[CertChainAndKey]): Builder
    def withPemsToTrustStore(pems: List[String]): Builder
    def withWipedTrustStore: Builder
    def withSendBufferSize(size: Int): Builder
    def withVerifyHostCallback(cb: String => SyncIO[Boolean]): Builder
    def withDisabledX509Verification: Builder
    def withMaxCertChainDepth(maxDepth: Short): Builder
    def withDHParams(dhparams: String): Builder
    def withCipherPreferences(version: String): Builder
  }

  private final case class BuilderImpl(
      certKeyPairs: List[CertChainAndKey] = Nil,
      pems: List[String] = Nil,
      wipedTrustStore: Boolean = false,
      sendBufferSize: Option[Int] = None,
      verifyHostCallback: Option[String => SyncIO[Boolean]] = None,
      disabledX509Verification: Boolean = false,
      maxCertChainDepth: Option[Short] = None,
      dhParams: Option[String] = None,
      cipherPreferences: Option[String] = None
  ) extends Builder {

    def build[F[_]](implicit F: Sync[F]): Resource[F, S2nConfig] = for {
      gcRoot <- mkGcRoot[F]

      cfg <- Resource.make(F.delay(guard(s2n_config_new())))(cfg =>
        F.delay(guard_(s2n_config_free(cfg)))
      )

      _ <- F.delay {
        guard_(s2n_config_set_async_pkey_callback(cfg, asyncPkeyCallback[F](_, _)))
      }.toResource

      _ <- F.delay(guard_(s2n_config_wipe_trust_store(cfg))).whenA(wipedTrustStore).toResource

      _ <- certKeyPairs.traverse_ { pair =>
        pair.toS2n.evalMap { ptr =>
          F.delay(guard_(s2n_config_add_cert_chain_and_key_to_store(cfg, ptr)))
        }
      }

      _ <- pems.traverse_ { pem =>
        F.delay {
          guard_(s2n_config_add_pem_to_trust_store(cfg, toCStringArray(pem).atUnsafe(0)))
        }
      }.toResource

      _ <- sendBufferSize
        .traverse(size => F.delay(guard_(s2n_config_set_send_buffer_size(cfg, size.toUInt))))
        .toResource

      _ <- verifyHostCallback.traverse_ { cb =>
        F.delay(gcRoot.add(cb)) *>
          F.delay {
            guard_(s2n_config_set_verify_host_callback(cfg, s2nVerifyHostFn(_, _, _), toPtr(cb)))
          }
      }.toResource

      _ <- F
        .delay(guard_(s2n_config_disable_x509_verification(cfg)))
        .whenA(disabledX509Verification)
        .toResource

      _ <- maxCertChainDepth.traverse_ { depth =>
        F.delay(guard_(s2n_config_set_max_cert_chain_depth(cfg, depth.toUShort)))
      }.toResource

      _ <- dhParams.traverse_ { pem =>
        F.delay {
          guard_(s2n_config_add_dhparams(cfg, toCStringArray(pem).atUnsafe(0)))
        }
      }.toResource

      _ <- cipherPreferences.traverse_ { version =>
        F.delay {
          guard_(s2n_config_set_cipher_preferences(cfg, toCStringArray(version).atUnsafe(0)))
        }
      }.toResource
    } yield new S2nConfig(cfg)

    def withCertChainAndKeysToStore(certKeyPairs: List[CertChainAndKey]): Builder =
      copy(certKeyPairs = certKeyPairs)

    def withPemsToTrustStore(pems: List[String]): Builder =
      copy(pems = pems)

    def withWipedTrustStore: Builder = copy(wipedTrustStore = true)

    def withSendBufferSize(size: Int): Builder = copy(sendBufferSize = Some(size))

    def withVerifyHostCallback(cb: String => SyncIO[Boolean]): Builder =
      copy(verifyHostCallback = Some(cb))

    def withDisabledX509Verification: Builder = copy(disabledX509Verification = true)

    def withMaxCertChainDepth(maxDepth: Short): Builder = copy(maxCertChainDepth = Some(maxDepth))

    def withDHParams(dhparams: String): Builder = copy(dhParams = Some(dhparams))

    def withCipherPreferences(version: String): Builder = copy(cipherPreferences = Some(version))

  }

  // we manually schedule expensive private-key operations so we can add fairness boundaries
  private def asyncPkeyCallback[F[_]](conn: Ptr[s2n_connection], op: Ptr[s2n_async_pkey_op]): CInt =
    try {
      val data = fromPtr[S2nConnection.ConnectionContext[F]](guard(s2n_connection_get_ctx(conn)))
      import data._
      implicit val F = async

      val cert = guard(s2n_connection_get_selected_cert(conn))
      val key = guard(s2n_cert_chain_and_key_get_private_key(cert))

      val task = F.cede *>
        F.delay {
          guard_(s2n_async_pkey_op_perform(op, key))
          guard_(s2n_async_pkey_op_apply(op, conn))
        }.guarantee(F.cede)
      val cleanup = F.delay(guard_(s2n_async_pkey_op_free(op)))

      privateKeyTasks.getAndUpdate(_ *> task)
      privateKeyCleanupTasks.getAndUpdate(_.guarantee(cleanup))

      S2N_SUCCESS
    } catch {
      case NonFatal(_) =>
        guard_(s2n_async_pkey_op_free(op))
        S2N_FAILURE
    }

}
