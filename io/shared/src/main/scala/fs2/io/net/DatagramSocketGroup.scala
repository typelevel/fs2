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
import com.comcast.ip4s._

trait DatagramSocketGroup[F[_]] {

  /** Creates a UDP socket bound to the specified address.
    *
    * @param address
    *   address to bind to; defaults to all interfaces
    * @param port
    *   port to bind to; defaults to an ephemeral port
    * @param options
    *   socket options to apply to the underlying socket
    * @param protocolFamily
    *   protocol family to use when opening the supporting `DatagramChannel`
    */
  def openDatagramSocket(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[DatagramSocketOption] = Nil,
      protocolFamily: Option[DatagramSocketGroup.ProtocolFamily] = None
  ): Resource[F, DatagramSocket[F]]
}

private[net] object DatagramSocketGroup extends DatagramSocketGroupCompanionPlatform
