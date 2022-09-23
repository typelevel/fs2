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

import events.EventEmitter

package object net {
  @js.native
  @JSImport("net", "createServer")
  private[io] def createServer(
      options: ServerOptions,
      connectionListener: js.Function1[Socket, Unit]
  ): Server =
    js.native
}

package net {

  @js.native
  private[io] trait Server extends EventEmitter {

    def address(): ServerAddress = js.native

    def listening: Boolean = js.native

    def close(cb: js.Function1[js.UndefOr[js.Error], Unit]): Server = js.native

    def listen(path: String, cb: js.Function0[Unit]): Server = js.native

    def listen(port: Int, connectListener: js.Function0[Unit]): Server = js.native

    def listen(port: Int, host: String, connectListener: js.Function0[Unit]): Server = js.native

  }

  @js.native
  private[io] trait ServerAddress extends js.Object {
    def address: String = js.native
    def port: Int = js.native
  }

  @nowarn
  private[io] trait ServerOptions extends js.Object {

    var allowHalfOpen: js.UndefOr[Boolean] = js.undefined

    var pauseOnConnect: js.UndefOr[Boolean] = js.undefined
  }

  @nowarn
  private[io] trait ListenOptions extends js.Object {
    var path: js.UndefOr[String] = js.undefined
  }

  @nowarn
  private[io] trait SocketOptions extends js.Object {

    var allowHalfOpen: js.UndefOr[Boolean] = js.undefined

  }

  @JSImport("net", "Socket")
  @js.native
  private[io] class Socket extends fs2.io.Duplex {

    def this(options: SocketOptions) = this()

    def connect(path: String, connectListener: js.Function0[Unit]): Socket = js.native

    def connect(port: Int, host: String, connectListener: js.Function0[Unit]): Socket = js.native

    def destroyed: Boolean = js.native

    def readyState: String = js.native

    def localAddress: js.UndefOr[String] = js.native

    def localPort: js.UndefOr[Int] = js.native

    def remoteAddress: js.UndefOr[String] = js.native

    def remotePort: js.UndefOr[Int] = js.native

    def end(): Socket = js.native

    def setEncoding(encoding: String): Socket = js.native

    def setKeepAlive(enable: Boolean): Socket = js.native

    def setNoDelay(noDelay: Boolean): Socket = js.native

    def setTimeout(timeout: Double): Socket = js.native

  }

}
