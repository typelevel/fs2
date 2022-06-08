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

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

import events.EventEmitter

package object dgram {

  @js.native
  @JSImport("dgram", "createSocket")
  @nowarn
  private[io] def createSocket(`type`: String): Socket =
    js.native

}

package dgram {

  @js.native
  @nowarn
  private[io] trait Socket extends EventEmitter {

    def address(): AddressInfo = js.native

    def bind(options: BindOptions, cb: js.Function0[Unit]): Unit = js.native

    def addMembership(multicastAddress: String, multicastInterface: String): Unit = js.native

    def dropMembership(multicastAddress: String, multicastInterface: String): Unit = js.native

    def addSourceSpecificMembership(
        sourceAddress: String,
        groupAddress: String,
        multicastInterface: String
    ): Unit = js.native

    def dropSourceSpecificMembership(
        sourceAddress: String,
        groupAddress: String,
        multicastInterface: String
    ): Unit = js.native

    def close(cb: js.Function0[Unit]): Unit = js.native

    def send(msg: Uint8Array, port: Int, address: String, cb: js.Function1[js.Error, Unit]): Unit =
      js.native

    def setBroadcast(flag: Boolean): Unit = js.native

    def setMulticastInterface(multicastInterface: String): Unit = js.native

    def setMulticastLoopback(flag: Boolean): Unit = js.native

    def setMulticastTTL(ttl: Int): Unit = js.native

    def setRecvBufferSize(size: Int): Unit = js.native

    def setSendBufferSize(size: Int): Unit = js.native

    def setTTL(ttl: Int): Unit = js.native

  }

  @js.native
  private[io] trait AddressInfo extends js.Object {
    def address: String = js.native
    def family: Int = js.native
    def port: Int = js.native
  }

  private[io] trait BindOptions extends js.Object {
    var port: js.UndefOr[Int] = js.undefined
    var address: js.UndefOr[String] = js.undefined
  }

  @js.native
  private[io] trait RemoteInfo extends js.Object {
    def address: String = js.native
    def family: Int = js.native
    def port: Int = js.native
    def size: Int = js.native
  }

}
