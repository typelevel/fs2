package fs2
package io
package tls

import scala.collection.JavaConverters._

import java.security.AlgorithmConstraints
import javax.net.ssl.{SNIMatcher, SNIServerName, SSLParameters}

/**
  * Parameters used in creation of a TLS/DTLS session.
  * See `javax.net.ssl.SSLParameters` for detailed documentation on each parameter.
  *
  * Note: `applicationProtocols`, `enableRetransmissions`, and `maximumPacketSize` require Java 9+.
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

  private[tls] def toSSLParameters: SSLParameters = {
    val p = new SSLParameters()
    algorithmConstraints.foreach(p.setAlgorithmConstraints)
    applicationProtocols.foreach(ap => p.setApplicationProtocols(ap.toArray))
    cipherSuites.foreach(cs => p.setCipherSuites(cs.toArray))
    enableRetransmissions.foreach(p.setEnableRetransmissions)
    endpointIdentificationAlgorithm.foreach(p.setEndpointIdentificationAlgorithm)
    maximumPacketSize.foreach(p.setMaximumPacketSize)
    protocols.foreach(ps => p.setProtocols(ps.toArray))
    serverNames.foreach(sn => p.setServerNames(sn.asJava))
    sniMatchers.foreach(sm => p.setSNIMatchers(sm.asJava))
    p.setUseCipherSuitesOrder(useCipherSuitesOrder)
    p.setNeedClientAuth(needClientAuth)
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
      wantClientAuth: Boolean = false
  ): TLSParameters =
    new DefaultTLSParameters(
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
      wantClientAuth
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
      wantClientAuth: Boolean
  ) extends TLSParameters
}
