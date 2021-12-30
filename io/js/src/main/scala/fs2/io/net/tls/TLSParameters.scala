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

import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.ThrowableOps._
import fs2.internal.jsdeps.node.tlsMod
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js
import scala.scalajs.js.|
import cats.syntax.all._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher

/** Parameters used in creation of a TLS session.
  * See [[https://nodejs.org/api/tls.html]] for detailed documentation on each parameter.
  */
sealed trait TLSParameters {
  val requestCert: Option[Boolean]
  val rejectUnauthorized: Option[Boolean]
  val alpnProtocols: Option[List[String]]
  val sniCallback: Option[TLSParameters.SNICallback]

  val session: Option[SSLSession]
  val requestOCSP: Option[Boolean]

  val pskCallback: Option[TLSParameters.PSKCallback]
  val servername: Option[String]
  val checkServerIdentity: Option[TLSParameters.CheckServerIdentity]
  val minDHSize: Option[Int]

  private[tls] def toTLSSocketOptions[F[_]: Async](
      dispatcher: Dispatcher[F]
  ): tlsMod.TLSSocketOptions = {
    val options = tlsMod.TLSSocketOptions()
    setCommonOptions(options, dispatcher)
    session.map(s => Chunk.byteVector(s.raw).toBuffer).foreach(options.setSession(_))
    requestOCSP.foreach(options.setRequestOCSP(_))
    options
  }

  private[tls] def toConnectionOptions[F[_]: Async](
      dispatcher: Dispatcher[F]
  ): tlsMod.ConnectionOptions = {
    val options = tlsMod.ConnectionOptions()
    setCommonOptions(options, dispatcher)
    session.map(s => Chunk.byteVector(s.raw).toBuffer).foreach(options.setSession(_))
    pskCallback.map(_.toJS).foreach(options.setPskCallback(_))
    servername.foreach(options.setServername(_))
    checkServerIdentity.map(_.toJS).foreach(options.setCheckServerIdentity(_))
    minDHSize.map(_.toDouble).foreach(options.setMinDHSize(_))
    options
  }

  private def setCommonOptions[F[_]: Async](
      options: tlsMod.CommonConnectionOptions,
      dispatcher: Dispatcher[F]
  ): Unit = {
    requestCert.foreach(options.setRequestCert(_))
    rejectUnauthorized.foreach(options.setRejectUnauthorized(_))
    alpnProtocols
      .map(_.map(x => x: String | Uint8Array).toJSArray)
      .foreach(options.setALPNProtocols(_))
    sniCallback.map(_.toJS(dispatcher)).foreach(options.setSNICallback(_))
  }
}

object TLSParameters {
  val Default: TLSParameters = TLSParameters()

  def apply(
      requestCert: Option[Boolean] = None,
      rejectUnauthorized: Option[Boolean] = None,
      alpnProtocols: Option[List[String]] = None,
      sniCallback: Option[TLSParameters.SNICallback] = None,
      session: Option[SSLSession] = None,
      requestOCSP: Option[Boolean] = None,
      pskCallback: Option[TLSParameters.PSKCallback] = None,
      servername: Option[String] = None,
      checkServerIdentity: Option[TLSParameters.CheckServerIdentity] = None,
      minDHSize: Option[Int] = None
  ): TLSParameters = DefaultTLSParameters(
    requestCert,
    rejectUnauthorized,
    alpnProtocols,
    sniCallback,
    session,
    requestOCSP,
    pskCallback,
    servername,
    checkServerIdentity,
    minDHSize
  )

  private case class DefaultTLSParameters(
      requestCert: Option[Boolean],
      rejectUnauthorized: Option[Boolean],
      alpnProtocols: Option[List[String]],
      sniCallback: Option[TLSParameters.SNICallback],
      session: Option[SSLSession],
      requestOCSP: Option[Boolean],
      pskCallback: Option[TLSParameters.PSKCallback],
      servername: Option[String],
      checkServerIdentity: Option[TLSParameters.CheckServerIdentity],
      minDHSize: Option[Int]
  ) extends TLSParameters

  trait SNICallback {
    def apply[F[_]: Async](servername: String): F[Either[Throwable, Option[SecureContext]]]
    private[TLSParameters] def toJS[F[_]](dispatcher: Dispatcher[F])(implicit
        F: Async[F]
    ): js.Function2[String, js.Function2[js.Error | Null, js.UndefOr[
      tlsMod.SecureContext
    ], Unit], Unit] = { (servername, cb) =>
      dispatcher.unsafeRunAndForget {
        import SecureContext.ops
        apply(servername).flatMap {
          case Left(ex)         => F.delay(cb(ex.toJSError, null))
          case Right(Some(ctx)) => F.delay(cb(null, ctx.toJS))
          case Right(None)      => F.delay(cb(null, null))
        }
      }
    }
  }

  trait PSKCallback {
    def apply(hint: Option[String]): Option[PSKCallbackNegotation]

    private[TLSParameters] def toJS
        : js.Function1[String | Null, tlsMod.PSKCallbackNegotation | Null] = { hint =>
      apply(Option(hint.asInstanceOf[String])).map(_.toJS).getOrElse(null)
    }
  }

  final case class PSKCallbackNegotation(psk: Chunk[Byte], identity: String) {
    private[TLSParameters] def toJS = tlsMod.PSKCallbackNegotation(identity, psk.toNodeUint8Array)
  }

  trait CheckServerIdentity {
    def apply(servername: String, cert: Chunk[Byte]): Either[Throwable, Unit]

    private[TLSParameters] def toJS
        : js.Function2[String, tlsMod.PeerCertificate, js.UndefOr[js.Error]] = {
      (servername, cert) =>
        apply(servername, cert.raw.toChunk) match {
          case Left(ex) => ex.toJSError
          case _        => ()
        }
    }
  }
}
