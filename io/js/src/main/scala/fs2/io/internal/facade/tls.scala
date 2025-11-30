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

package fs2.io.internal.facade

import fs2.io.net.tls.SecureContext
import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

import net.Socket

@nowarn212("cat=unused")
@nowarn3("msg=unused import")
private[io] object tls {

  import scala.scalajs.js.|

  @js.native
  @JSImport("tls", "connect")
  def connect(options: TLSConnectOptions): TLSSocket =
    js.native

  @js.native
  @JSImport("tls", "createSecureContext")
  def createSecureContext(
      options: js.UndefOr[SecureContextOptions] = js.undefined
  ): SecureContext =
    js.native

  trait SecureContextOptions extends js.Object {

    var ca: js.UndefOr[js.Array[String | Uint8Array]] = js.undefined

    var cert: js.UndefOr[js.Array[String | Uint8Array]] = js.undefined

    var sigalgs: js.UndefOr[String] = js.undefined

    var ciphers: js.UndefOr[String] = js.undefined

    var clientCertEngine: js.UndefOr[String] = js.undefined

    var crl: js.UndefOr[js.Array[String | Uint8Array]] = js.undefined

    var dhparam: js.UndefOr[String | Uint8Array] = js.undefined

    var ecdhCurve: js.UndefOr[String] = js.undefined

    var honorCipherOrder: js.UndefOr[Boolean] = js.undefined

    var key: js.UndefOr[js.Array[Key]] = js.undefined

    var privateKeyEngine: js.UndefOr[String] = js.undefined

    var privateKeyIdentifier: js.UndefOr[String] = js.undefined

    var maxVersion: js.UndefOr[String] = js.undefined

    var minVersion: js.UndefOr[String] = js.undefined

    var passphrase: js.UndefOr[String] = js.undefined

    var pfx: js.UndefOr[js.Array[Pfx]] = js.undefined

    var secureOptions: js.UndefOr[Double] = js.undefined

    var sessionIdContext: js.UndefOr[String] = js.undefined

    var ticketKeys: js.UndefOr[Uint8Array] = js.undefined

    var sessionTimeout: js.UndefOr[Double] = js.undefined

  }

  trait Key extends js.Object {

    val pem: String | Uint8Array

    var passphrase: js.UndefOr[String] = js.undefined

  }

  trait Pfx extends js.Object {

    val buf: String | Uint8Array

    var passphrase: js.UndefOr[String] = js.undefined

  }

  trait TLSConnectOptions extends js.Object {

    var secureContext: js.UndefOr[SecureContext] = js.undefined

    var enableTrace: js.UndefOr[Boolean] = js.undefined

    var socket: js.UndefOr[fs2.io.Duplex] = js.undefined

    var requestCert: js.UndefOr[Boolean] = js.undefined

    var rejectUnauthorized: js.UndefOr[Boolean] = js.undefined

    var ALPNProtocols: js.UndefOr[js.Array[String]] = js.undefined

    var SNICallback: js.UndefOr[js.Function2[String, js.Function2[js.Error, js.UndefOr[
      SecureContext
    ], Unit], Unit]] = js.undefined

    var session: js.UndefOr[Uint8Array] = js.undefined

    var pskCallback: js.UndefOr[js.Function1[String, PSKCallbackNegotation]] = js.undefined

    var servername: js.UndefOr[String] = js.undefined

    var checkServerIdentity
        : js.UndefOr[js.Function2[String, PeerCertificate, js.UndefOr[js.Error]]] =
      js.undefined

    var minDHSize: js.UndefOr[Int] = js.undefined

  }

  trait PSKCallbackNegotation extends js.Object {

    var psk: js.UndefOr[Uint8Array] = js.undefined

    var identity: js.UndefOr[String] = js.undefined

  }

  @js.native
  trait PeerCertificate extends js.Object {

    def raw: Uint8Array = js.native

  }

  trait TLSSocketOptions extends js.Object {

    var secureContext: js.UndefOr[SecureContext] = js.undefined

    var enableTrace: js.UndefOr[Boolean] = js.undefined

    var isServer: js.UndefOr[Boolean] = js.undefined

    var session: js.UndefOr[Uint8Array] = js.undefined

    var requestOCSP: js.UndefOr[Boolean] = js.undefined

    var requestCert: js.UndefOr[Boolean] = js.undefined

    var rejectUnauthorized: js.UndefOr[Boolean] = js.undefined

    var ALPNProtocols: js.UndefOr[js.Array[String]] = js.undefined

    var SNICallback: js.UndefOr[js.Function2[String, js.Function2[js.Error, js.UndefOr[
      SecureContext
    ], Unit], Unit]] = js.undefined

  }

  @JSImport("tls", "TLSSocket")
  @js.native
  class TLSSocket extends Socket {

    def this(@unused socket: fs2.io.Duplex, @unused options: TLSSocketOptions) = this()

    def alpnProtocol: String | Boolean = js.native

    def ssl: SSL = js.native

    def getSession(): js.UndefOr[Uint8Array] = js.native

  }

  @js.native
  trait SSL extends js.Object {
    def verifyError(): js.Error = js.native
  }

}
