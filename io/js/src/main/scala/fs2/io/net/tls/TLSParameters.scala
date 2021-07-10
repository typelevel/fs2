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

import fs2.internal.jsdeps.node.tlsMod
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.|
import scala.scalajs.js.typedarray.Uint8Array

/** Parameters used in creation of a TLS/DTLS session.
  * See `javax.net.ssl.SSLParameters` for detailed documentation on each parameter.
  */
sealed trait TLSParameters {
  val applicationProtocols: Option[List[String]]

  private[tls] def toTLSSocketOptions(context: tlsMod.SecureContext): tlsMod.TLSSocketOptions = {
    val options = tlsMod.TLSSocketOptions().setSecureContext(context)
    applicationProtocols.foreach(protocols =>
      options.setALPNProtocols(protocols.view.map(x => x: String | Uint8Array).toJSArray)
    )
    options
  }

}

object TLSParameters {
  val Default: TLSParameters = TLSParameters()

  def apply(
      applicationProtocols: Option[List[String]] = None
  ): TLSParameters = DefaultTLSParameters(
    applicationProtocols
  )

  private case class DefaultTLSParameters(
      applicationProtocols: Option[List[String]]
  ) extends TLSParameters
}
