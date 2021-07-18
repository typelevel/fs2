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
package io.net.tls

import fs2.io.internal.ByteChunkOps._
import fs2.internal.jsdeps.node.tlsMod
import scala.scalajs.js
import scala.scalajs.js.|
import scala.scalajs.js.JSConverters._
import cats.syntax.all._
import fs2.internal.jsdeps.node.bufferMod
import scala.concurrent.duration.FiniteDuration

/** A facade for Node.js `tls.SecureContext` */
@js.native
sealed trait SecureContext extends js.Object

object SecureContext {
  private[tls] implicit final class ops(private val context: SecureContext) extends AnyVal {
    private[tls] def toJS = context.asInstanceOf[tlsMod.SecureContext]
  }

  def default: SecureContext = fromJS(tlsMod.createSecureContext())

  /** @see [[https://nodejs.org/api/tls.html#tls_tls_createsecurecontext_options]] */
  def apply(
      ca: Option[Seq[Either[Chunk[Byte], String]]] = None,
      cert: Option[Seq[Either[Chunk[Byte], String]]] = None,
      ciphers: Option[String] = None,
      clientCertEngine: Option[String] = None,
      crl: Option[Seq[Either[Chunk[Byte], String]]] = None,
      dhparam: Option[Either[Chunk[Byte], String]] = None,
      ecdhCurve: Option[String] = None,
      honorCipherOrder: Option[Boolean] = None,
      key: Option[Seq[Key]] = None,
      maxVersion: Option[SecureVersion] = None,
      minVersion: Option[SecureVersion] = None,
      passphrase: Option[String] = None,
      pfx: Option[Seq[Pfx]] = None,
      privateKeyEngine: Option[String] = None,
      privateKeyIdentifier: Option[String] = None,
      secureOptions: Option[Long] = None,
      sessionIdContext: Option[String] = None,
      sessionTimeout: Option[FiniteDuration] = None,
      sigalgs: Option[String] = None,
      ticketKeys: Option[Chunk[Byte]] = None
  ): SecureContext = {
    val options = tlsMod.SecureContextOptions()

    ca.map(toJS).foreach(options.setCa(_))
    cert.map(toJS).foreach(options.setCert(_))
    ciphers.foreach(options.setCiphers)
    clientCertEngine.foreach(options.setClientCertEngine)
    crl.map(toJS).foreach(options.setCrl(_))
    dhparam.map(toJS).foreach(options.setDhparam)
    ecdhCurve.foreach(options.setEcdhCurve)
    honorCipherOrder.foreach(options.setHonorCipherOrder)
    key
      .map(_.view.map(_.toJS: bufferMod.global.Buffer | tlsMod.KeyObject).toJSArray)
      .foreach(options.setKey(_))
    maxVersion.map(_.toJS).foreach(options.setMaxVersion)
    minVersion.map(_.toJS).foreach(options.setMinVersion)
    passphrase.foreach(options.setPassphrase)
    pfx.map(_.view.map(_.toJS))
    privateKeyEngine.foreach(options.setPrivateKeyEngine)
    privateKeyIdentifier.foreach(options.setPrivateKeyIdentifier)
    secureOptions.map(_.toDouble).foreach(options.setSecureOptions)
    sessionIdContext.foreach(options.setSessionIdContext)
    sessionTimeout.map(_.toSeconds.toDouble).foreach(options.setSessionTimeout)
    sigalgs.foreach(options.setSigalgs)
    ticketKeys.map(_.toBuffer).foreach(options.setTicketKeys)

    fromJS(tlsMod.createSecureContext(options))
  }

  def fromJS(secureContext: js.Any): SecureContext = secureContext.asInstanceOf[SecureContext]

  sealed abstract class SecureVersion {
    private[SecureContext] def toJS: tlsMod.SecureVersion
  }
  object SecureVersion {
    case object TLSv1 extends SecureVersion {
      private[SecureContext] def toJS = tlsMod.SecureVersion.TLSv1
    }
    case object `TLSv1.1` extends SecureVersion {
      private[SecureContext] def toJS = tlsMod.SecureVersion.TLSv1Dot1
    }
    case object `TLSv1.2` extends SecureVersion {
      private[SecureContext] def toJS = tlsMod.SecureVersion.TLSv1Dot2
    }
    case object `TLSv1.3` extends SecureVersion {
      private[SecureContext] def toJS = tlsMod.SecureVersion.TLSv1Dot3
    }
  }

  final case class Key(pem: Either[Chunk[Byte], String], passphrase: Option[String] = None) {
    private[SecureContext] def toJS = {
      val key = tlsMod.KeyObject(SecureContext.toJS(pem))
      passphrase.foreach(key.setPassphrase)
      key
    }
  }

  final case class Pfx(buf: Either[Chunk[Byte], String], passphrase: Option[String] = None) {
    private[SecureContext] def toJS = {
      val pfx = tlsMod.PxfObject(SecureContext.toJS(buf))
      passphrase.foreach(pfx.setPassphrase)
      pfx
    }
  }

  private def toJS(x: Either[Chunk[Byte], String]): String | bufferMod.global.Buffer = x
    .bimap(
      _.toBuffer: String | bufferMod.global.Buffer,
      x => x: String | bufferMod.global.Buffer
    )
    .merge

  private def toJS(
      x: Seq[Either[Chunk[Byte], String]]
  ): js.Array[String | bufferMod.global.Buffer] =
    x.view.map(toJS).toJSArray
}
