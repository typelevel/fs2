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

import cats.syntax.all._
import fs2.io.internal.facade

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.scalajs.js.|
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.typedarray.Uint8Array

/** A facade for Node.js `tls.SecureContext` */
@js.native
sealed trait SecureContext extends js.Object

object SecureContext {

  def default: SecureContext = fromJS(facade.tls.createSecureContext())

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
    val options = new facade.SecureContextOptions {}

    options.ca = ca.map(toJS).orUndefined
    options.cert = cert.map(toJS).orUndefined
    options.ciphers = ciphers.orUndefined
    options.clientCertEngine = clientCertEngine.orUndefined
    options.crl = crl.map(toJS).orUndefined
    options.dhparam = dhparam.map(toJS).orUndefined
    options.ecdhCurve = ecdhCurve.orUndefined
    options.honorCipherOrder = honorCipherOrder.orUndefined
    options.key = key.map(_.view.map(_.toJS).toJSArray).orUndefined
    options.maxVersion = maxVersion.map(_.toJS).orUndefined
    options.minVersion = minVersion.map(_.toJS).orUndefined
    options.passphrase = passphrase.orUndefined
    options.pfx = pfx.map(_.view.map(_.toJS).toJSArray).orUndefined
    options.privateKeyEngine = privateKeyEngine.orUndefined
    options.privateKeyIdentifier = privateKeyIdentifier.orUndefined
    options.secureOptions = secureOptions.map(_.toDouble).orUndefined
    options.sessionIdContext = sessionIdContext.orUndefined
    options.sessionTimeout = sessionTimeout.map(_.toSeconds.toDouble).orUndefined
    options.sigalgs = sigalgs.orUndefined
    options.ticketKeys = ticketKeys.map(_.toUint8Array).orUndefined

    facade.tls.createSecureContext(options)
  }

  def fromJS(secureContext: js.Any): SecureContext = secureContext.asInstanceOf[SecureContext]

  sealed abstract class SecureVersion {
    private[SecureContext] def toJS: String
  }
  object SecureVersion {
    case object TLSv1 extends SecureVersion {
      private[SecureContext] def toJS = "TLSv1"
    }
    case object `TLSv1.1` extends SecureVersion {
      private[SecureContext] def toJS = "TLSv1.1"
    }
    case object `TLSv1.2` extends SecureVersion {
      private[SecureContext] def toJS = "TLSv1.2"
    }
    case object `TLSv1.3` extends SecureVersion {
      private[SecureContext] def toJS = "TLSv1.3"
    }
  }

  final case class Key(pem: Either[Chunk[Byte], String], passphrase: Option[String] = None) {
    outer =>
    private[SecureContext] def toJS = new facade.Key {
      val pem = SecureContext.toJS(outer.pem)
      passphrase = outer.passphrase.orUndefined
    }
  }

  final case class Pfx(buf: Either[Chunk[Byte], String], passphrase: Option[String] = None) {
    outer =>
    private[SecureContext] def toJS = new facade.Pfx {
      val buf = SecureContext.toJS(outer.buf)
      passphrase = outer.passphrase.orUndefined
    }
  }

  private def toJS(x: Either[Chunk[Byte], String]): String | Uint8Array = x
    .bimap(
      _.toUint8Array: String | Uint8Array,
      x => x: String | Uint8Array
    )
    .merge

  private def toJS(
      x: Seq[Either[Chunk[Byte], String]]
  ): js.Array[String | Uint8Array] =
    x.view.map(toJS).toJSArray
}
