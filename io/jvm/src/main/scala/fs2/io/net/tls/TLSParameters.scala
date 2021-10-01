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

import java.security.AlgorithmConstraints
import javax.net.ssl.{SNIMatcher, SNIServerName, SSLEngine, SSLParameters}

import CollectionCompat._

/** Parameters used in creation of a TLS/DTLS session.
  * See `javax.net.ssl.SSLParameters` for detailed documentation on each parameter.
  *
  * Note: `applicationProtocols`, `enableRetransmissions`, `maximumPacketSize`, and
  * `handshakeApplicationProtocolSelector` require Java 9+.
  */
sealed trait TLSParameters {
  val algorithmConstraints: Option[AlgorithmConstraints]
  val applicationProtocols: Option[List[String]]
  val cipherSuites: Option[List[String]]
  val enableRetransmissions: Option[Boolean]
  val endpointIdentificationAlgorithm: Option[String]
  val maximumPacketSize: Option[Int]
  val protocols: Option[List[String]]
  val serverNames: Option[List[SNIServerName]]
  val sniMatchers: Option[List[SNIMatcher]]
  val useCipherSuitesOrder: Boolean
  val needClientAuth: Boolean
  val wantClientAuth: Boolean
  val handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String]

  /**  Converts to a `javax.net.ssl.SSLParameters` instance.
    *
    * `needClientAuth` and `wantClientAuth` are mutually exclusive on `SSLParameters`. If both set on this `TLSParameters`, then `needClientAuth` takes precedence.
    */
  def toSSLParameters: SSLParameters = {
    val p = new SSLParametersCompat()
    algorithmConstraints.foreach(p.setAlgorithmConstraints)
    applicationProtocols.foreach(ap => p.setApplicationProtocols(ap.toArray))
    cipherSuites.foreach(cs => p.setCipherSuites(cs.toArray))
    enableRetransmissions.foreach(p.setEnableRetransmissionsCompat)
    endpointIdentificationAlgorithm.foreach(p.setEndpointIdentificationAlgorithm)
    maximumPacketSize.foreach(p.setMaximumPacketSizeCompat)
    protocols.foreach(ps => p.setProtocols(ps.toArray))
    serverNames.foreach(sn => p.setServerNames(sn.asJava))
    sniMatchers.foreach(sm => p.setSNIMatchers(sm.asJava))
    p.setUseCipherSuitesOrder(useCipherSuitesOrder)
    if (needClientAuth)
      p.setNeedClientAuth(needClientAuth)
    else if (wantClientAuth)
      p.setWantClientAuth(wantClientAuth)
    p
  }
}

object TLSParameters {
  val Default: TLSParameters = TLSParameters()

  def apply(
      algorithmConstraints: Option[AlgorithmConstraints] = None,
      applicationProtocols: Option[List[String]] = None,
      cipherSuites: Option[List[String]] = None,
      enableRetransmissions: Option[Boolean] = None,
      endpointIdentificationAlgorithm: Option[String] = None,
      maximumPacketSize: Option[Int] = None,
      protocols: Option[List[String]] = None,
      serverNames: Option[List[SNIServerName]] = None,
      sniMatchers: Option[List[SNIMatcher]] = None,
      useCipherSuitesOrder: Boolean = false,
      needClientAuth: Boolean = false,
      wantClientAuth: Boolean = false,
      handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String] = None
  ): TLSParameters =
    DefaultTLSParameters(
      algorithmConstraints,
      applicationProtocols,
      cipherSuites,
      enableRetransmissions,
      endpointIdentificationAlgorithm,
      maximumPacketSize,
      protocols,
      serverNames,
      sniMatchers,
      useCipherSuitesOrder,
      needClientAuth,
      wantClientAuth,
      handshakeApplicationProtocolSelector
    )

  // For binary compatibility
  def apply(
      algorithmConstraints: Option[AlgorithmConstraints],
      applicationProtocols: Option[List[String]],
      cipherSuites: Option[List[String]],
      enableRetransmissions: Option[Boolean],
      endpointIdentificationAlgorithm: Option[String],
      maximumPacketSize: Option[Int],
      protocols: Option[List[String]],
      serverNames: Option[List[SNIServerName]],
      sniMatchers: Option[List[SNIMatcher]],
      useCipherSuitesOrder: Boolean,
      needClientAuth: Boolean,
      wantClientAuth: Boolean
  ): TLSParameters =
    apply(
      algorithmConstraints,
      applicationProtocols,
      cipherSuites,
      enableRetransmissions,
      endpointIdentificationAlgorithm,
      maximumPacketSize,
      protocols,
      serverNames,
      sniMatchers,
      useCipherSuitesOrder,
      needClientAuth,
      wantClientAuth,
      None
    )

  private case class DefaultTLSParameters(
      algorithmConstraints: Option[AlgorithmConstraints],
      applicationProtocols: Option[List[String]],
      cipherSuites: Option[List[String]],
      enableRetransmissions: Option[Boolean],
      endpointIdentificationAlgorithm: Option[String],
      maximumPacketSize: Option[Int],
      protocols: Option[List[String]],
      serverNames: Option[List[SNIServerName]],
      sniMatchers: Option[List[SNIMatcher]],
      useCipherSuitesOrder: Boolean,
      needClientAuth: Boolean,
      wantClientAuth: Boolean,
      handshakeApplicationProtocolSelector: Option[(SSLEngine, List[String]) => String]
  ) extends TLSParameters
}
