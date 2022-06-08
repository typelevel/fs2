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

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import fs2.io.internal.ThrowableOps._
import fs2.io.internal.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** Parameters used in creation of a TLS session.
  * See [[https://nodejs.org/api/tls.html]] for detailed documentation on each parameter.
  */
sealed trait TLSParameters { outer =>
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
  ): facade.tls.TLSSocketOptions = {
    val options = new facade.tls.TLSSocketOptions {}
    outer.requestCert.foreach(options.requestCert = _)
    outer.rejectUnauthorized.foreach(options.rejectUnauthorized = _)
    alpnProtocols.map(_.toJSArray).foreach(options.ALPNProtocols = _)
    sniCallback.map(_.toJS(dispatcher)).foreach(options.SNICallback = _)
    outer.session.map(_.raw.toUint8Array).foreach(options.session = _)
    outer.requestOCSP.foreach(options.requestOCSP = _)
    options
  }

  private[tls] def toTLSConnectOptions[F[_]: Async](
      dispatcher: Dispatcher[F]
  ): facade.tls.TLSConnectOptions = {
    val options = new facade.tls.TLSConnectOptions {}
    outer.requestCert.foreach(options.requestCert = _)
    outer.rejectUnauthorized.foreach(options.rejectUnauthorized = _)
    alpnProtocols.map(_.toJSArray).foreach(options.ALPNProtocols = _)
    sniCallback.map(_.toJS(dispatcher)).foreach(options.SNICallback = _)
    outer.session.map(_.raw.toUint8Array).foreach(options.session = _)
    outer.pskCallback.map(_.toJS).foreach(options.pskCallback = _)
    outer.servername.foreach(options.servername = _)
    outer.checkServerIdentity.map(_.toJS).foreach(options.checkServerIdentity = _)
    outer.minDHSize.foreach(options.minDHSize = _)
    options
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
    ): js.Function2[String, js.Function2[js.Error, js.UndefOr[
      SecureContext
    ], Unit], Unit] = { (servername, cb) =>
      dispatcher.unsafeRunAndForget {
        apply(servername).flatMap {
          case Left(ex)         => F.delay(cb(ex.toJSError, null))
          case Right(Some(ctx)) => F.delay(cb(null, ctx))
          case Right(None)      => F.delay(cb(null, null))
        }
      }
    }
  }

  trait PSKCallback {
    def apply(hint: Option[String]): Option[PSKCallbackNegotation]

    private[TLSParameters] def toJS: js.Function1[String, facade.tls.PSKCallbackNegotation] =
      hint => apply(Option(hint)).map(_.toJS).orNull
  }

  final case class PSKCallbackNegotation(psk: Chunk[Byte], identity: String) { outer =>
    private[TLSParameters] def toJS = {
      val pskcbn = new facade.tls.PSKCallbackNegotation {}
      pskcbn.psk = outer.psk.toUint8Array
      pskcbn.identity = outer.identity
      pskcbn
    }
  }

  trait CheckServerIdentity {
    def apply(servername: String, cert: Chunk[Byte]): Either[Throwable, Unit]

    private[TLSParameters] def toJS
        : js.Function2[String, facade.tls.PeerCertificate, js.UndefOr[js.Error]] = {
      (servername, cert) =>
        apply(servername, Chunk.uint8Array(cert.raw)) match {
          case Left(ex) => ex.toJSError
          case _        => ()
        }
    }
  }
}
