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

import cats.effect.kernel.Resource
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import cats.effect.kernel.Async

/** Supports creation of client and server TCP sockets that all share
  * an underlying non-blocking channel group.
  */
trait SocketGroup[F[_]] {

  /** Opens a TCP connection to the specified server.
    *
    * The connection is closed when the resource is released.
    *
    * @param to      address of remote server
    * @param options socket options to apply to the underlying socket
    */
  def client(
      to: SocketAddress[Host],
      options: List[SocketOption] = List.empty
  ): Resource[F, Socket[F]]

  /** Creates a TCP server bound to specified address/port and returns a stream of
    * client sockets -- one per client that connects to the bound address/port.
    *
    * When the stream terminates, all open connections will terminate as well.
    * Because of this, make sure to handle errors in the client socket Streams.
    *
    * @param address            address to accept connections from; none for all interfaces
    * @param port               port to bind
    * @param options socket options to apply to the underlying socket
    */
  def server(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Stream[F, Socket[F]]

  /** Like [[server]] but provides the `SocketAddress` of the bound server socket before providing accepted sockets.
    *
    * Make sure to handle errors in the client socket Streams.
    */
  def serverResource(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])]
}

private[net] object SocketGroup extends SocketGroupCompanionPlatform {

  private[net] abstract class AbstractAsyncSocketGroup[F[_]: Async] extends SocketGroup[F] {
    def server(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Stream[F, Socket[F]] =
      Stream
        .resource(
          serverResource(
            address,
            port,
            options
          )
        )
        .flatMap { case (_, clients) => clients }
  }

}
