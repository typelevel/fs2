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

/** A facade for Node.js `tls.SecureContext` */
sealed trait SecureContext

object SecureContext {
  def default: SecureContext = fromJS(tlsMod.createSecureContext())

  /** @see [[https://nodejs.org/api/tls.html#tls_tls_createsecurecontext_options]] */
  def apply(
      ca: Seq[Either[Chunk[Byte], String]] = Seq.empty,
      cert: Seq[Either[Chunk[Byte], String]] = Seq.empty,
      ciphers: Option[String] = None,
      clientCertEngine: Option[String] = None,
      crl: Seq[Either[Chunk[Byte], String]] = Seq.empty,
      dhparam: Option[Either[Chunk[Byte], String]] = None,
      ecdhCurve: Option[String],
      honorCipherOrder: Option[Boolean],
      key: Seq[Key] = Seq.empty,
      maxVersion: Option[SecureVersion] = None,
      minVersion: Option[SecureVersion] = None,
      passphrase: Option[String],
      pfx: Seq[Pfx] = Seq.empty,
      privateKeyEngine: Option[String] = None,
      privateKeyIdentifier: Option[String] = None,
      secureOptions: Option[Long],
      sessionIdContext: Option[String],
      sessionTimeout: Option[Int],
      sigalgs: Option[String],
      ticketKeys: Option[Chunk[Byte]]
  ): SecureContext = {
    val options = tlsMod.SecureContextOptions()

    def liftEither(x: Either[Chunk[Byte], String]) = x
      .bimap(
        _.toBuffer: String | bufferMod.global.Buffer,
        x => x: String | bufferMod.global.Buffer
      )
      .merge

    def liftSeq(x: Seq[Either[Chunk[Byte], String]]) =
      x.view.map(liftEither).toJSArray

    if (ca.nonEmpty) options.ca = liftSeq(ca)
    if (cert.nonEmpty) options.cert = liftSeq(cert)
    ciphers.foreach(options.setCiphers)
    clientCertEngine.foreach(options.setClientCertEngine)
    if (crl.nonEmpty) options.crl = liftSeq(crl)
    dhparam.foreach(x => options.dhparam = liftEither(x))
    ecdhCurve.foreach(options.setEcdhCurve)
    honorCipherOrder.foreach(options.setHonorCipherOrder)
    if (key.nonEmpty) options.key = key.view.map { case Key(pem, passphrase) =>
      val key = tlsMod.KeyObject(liftEither(pem))
      passphrase.foreach(key.setPassphrase)
      key: bufferMod.global.Buffer | tlsMod.KeyObject
    }.toJSArray
    options.maxVersion

    fromJS(tlsMod.createSecureContext(options))
  }

  def fromJS(secureContext: js.Any): SecureContext = secureContext.asInstanceOf[SecureContext]

  sealed abstract class SecureVersion
  object SecureVersion {
    case object TLSv1 extends SecureVersion
    case object `TLSv1.1` extends SecureVersion
    case object `TLSv1.2` extends SecureVersion
    case object `TLSv1.3` extends SecureVersion
  }

  final case class Key(pem: Either[Chunk[Byte], String], passphrase: Option[String] = None)
  final case class Pfx(buf: Either[Chunk[Byte], String], passphrase: Option[String] = None)
}
