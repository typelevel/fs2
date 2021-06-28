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

import javax.net.ssl.SSLParameters

import CollectionCompat._

private[tls] trait TLSParametersPlatform { self: TLSParameters =>

  /**  Converts to a `javax.net.ssl.SSLParameters` instance.
    *
    * `needClientAuth` and `wantClientAuth` are mutually exclusive on `SSLParameters`. If both set on this `TLSParameters`, then `needClientAuth` takes precedence.
    */
  def toSSLParameters: SSLParameters = {
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
    if (needClientAuth)
      p.setNeedClientAuth(needClientAuth)
    else if (wantClientAuth)
      p.setWantClientAuth(wantClientAuth)
    p
  }
}

private[tls] trait TLSParametersSingletonPlatform {
  type AlgorithmConstraints = java.security.AlgorithmConstraints
  type SNIServerName = javax.net.ssl.SNIServerName
  type SNIMatcher = javax.net.ssl.SNIMatcher
  type SSLEngine = javax.net.ssl.SSLEngine
}
