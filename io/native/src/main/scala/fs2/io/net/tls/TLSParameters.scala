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

import cats.effect.SyncIO
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import s2n._
import s2nutil._

/** Parameters used in creation of an s2n_connection.
  * See [[https://github.com/aws/s2n-tls/ s2n-tls]] for detailed documentation on each parameter.
  */
sealed trait TLSParameters {
  val protocolPreferences: Option[List[String]]
  val cipherPreferences: Option[String]
  val serverName: Option[String]
  val verifyHostCallback: Option[String => SyncIO[Boolean]]
  val clientAuthType: Option[CertAuthType]

  private[tls] def withClientAuthType(clientAuthType: Option[CertAuthType]): TLSParameters

  private[tls] def configure[F[_]](
      conn: Ptr[s2n_connection]
  )(implicit F: Sync[F]): Resource[F, Unit] =
    for {
      gcRoot <- mkGcRoot

      _ <- protocolPreferences.toList.flatten.traverse { protocol =>
        F.delay {
          guard_ {
            s2n_connection_append_protocol_preference(
              conn,
              protocol.getBytes().atUnsafe(0),
              protocol.length.toByte
            )
          }
        }
      }.toResource

      _ <- cipherPreferences.traverse_ { version =>
        F.delay {
          guard_(s2n_connection_set_cipher_preferences(conn, toCStringArray(version).atUnsafe(0)))
        }
      }.toResource

      _ <- serverName.traverse { sn =>
        F.delay {
          guard_(s2n_set_server_name(conn, toCStringArray(sn).atUnsafe(0)))
        }
      }.toResource

      _ <- verifyHostCallback.traverse_ { cb =>
        F.delay(gcRoot.add(cb)) *>
          F.delay {
            guard_(
              s2n_connection_set_verify_host_callback(
                conn,
                s2nVerifyHostFn(_, _, _),
                toPtr(cb)
              )
            )
          }
      }.toResource

      _ <- clientAuthType.traverse_ { authType =>
        val ord = authType match {
          case CertAuthType.None     => S2N_CERT_AUTH_NONE
          case CertAuthType.Optional => S2N_CERT_AUTH_OPTIONAL
          case CertAuthType.Required => S2N_CERT_AUTH_REQUIRED
        }
        F.delay(guard_(s2n_connection_set_client_auth_type(conn, ord.toUInt)))
      }.toResource
    } yield ()
}

object TLSParameters {
  val Default: TLSParameters = TLSParameters()

  def apply(
      protocolPreferences: Option[List[String]] = None,
      cipherPreferences: Option[String] = None,
      serverName: Option[String] = None,
      verifyHostCallback: Option[String => SyncIO[Boolean]] = None,
      clientAuthType: Option[CertAuthType] = None
  ): TLSParameters = DefaultTLSParameters(
    protocolPreferences,
    cipherPreferences,
    serverName,
    verifyHostCallback,
    clientAuthType
  )

  private case class DefaultTLSParameters(
      protocolPreferences: Option[List[String]],
      cipherPreferences: Option[String],
      serverName: Option[String],
      verifyHostCallback: Option[String => SyncIO[Boolean]],
      clientAuthType: Option[CertAuthType]
  ) extends TLSParameters {
    def withClientAuthType(clientAuthType: Option[CertAuthType]) =
      copy(clientAuthType = clientAuthType)
  }

}
