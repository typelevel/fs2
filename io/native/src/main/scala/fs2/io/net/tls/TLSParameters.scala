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

import cats.effect.kernel.Sync

import scala.scalanative.unsafe._

import s2n._
import s2nutil._

/** Parameters used in creation of an s2n_connection.
  * See `javax.net.ssl.SSLParameters` for detailed documentation on each parameter.
  */
sealed trait TLSParameters {
  // val algorithmConstraints: Option[AlgorithmConstraints]
  // val applicationProtocols: Option[List[String]]
  // val cipherSuites: Option[List[String]]
  // val enableRetransmissions: Option[Boolean]
  // val endpointIdentificationAlgorithm: Option[String]
  // val maximumPacketSize: Option[Int]
  // val protocols: Option[List[String]]
  val serverName: Option[String]
  // val sniMatchers: Option[List[SNIMatcher]]
  // val useCipherSuitesOrder: Boolean
  // val needClientAuth: Boolean
  // val wantClientAuth: Boolean
  // val handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String]

  private[tls] def configure[F[_]](conn: Ptr[s2n_connection])(implicit F: Sync[F]): F[Unit] =
    F.delay {
      Zone { implicit z =>
        serverName.foreach(sn => guard_(s2n_set_server_name(conn, toCString(sn))))
      }
    }
}

object TLSParameters {
  val Default: TLSParameters = TLSParameters()

  def apply(
      // algorithmConstraints: Option[AlgorithmConstraints] = None,
      // applicationProtocols: Option[List[String]] = None,
      // cipherSuites: Option[List[String]] = None,
      // enableRetransmissions: Option[Boolean] = None,
      // endpointIdentificationAlgorithm: Option[String] = None,
      // maximumPacketSize: Option[Int] = None,
      // protocols: Option[List[String]] = None,
      serverName: Option[String] = None
      // sniMatchers: Option[List[SNIMatcher]] = None,
      // useCipherSuitesOrder: Boolean = false,
      // needClientAuth: Boolean = false,
      // wantClientAuth: Boolean = false,
      // handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String] = None
  ): TLSParameters = ???

  private case class DefaultTLSParameters(
      // algorithmConstraints: Option[AlgorithmConstraints],
      // applicationProtocols: Option[List[String]],
      // cipherSuites: Option[List[String]],
      // enableRetransmissions: Option[Boolean],
      // endpointIdentificationAlgorithm: Option[String],
      // maximumPacketSize: Option[Int],
      // protocols: Option[List[String]],
      serverName: Option[String]
      // sniMatchers: Option[List[SNIMatcher]],
      // useCipherSuitesOrder: Boolean,
      // needClientAuth: Boolean,
      // wantClientAuth: Boolean,
      // handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String]
  ) extends TLSParameters
}
