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

package fs2.io.net.unixsocket

import cats.effect.kernel.Resource
import fs2.Stream
import fs2.io.net.Socket
import fs2.io.net.SocketOption

/** Capability of working with AF_UNIX sockets.
  *
  * This trait provides functionality for creating Unix domain socket clients and servers.
  * Unix domain sockets provide inter-process communication on the same host using the filesystem
  * as the address namespace.
  *
  * Socket options can be configured for both client and server sockets, including:
  * - Standard socket options like SO_REUSEADDR, SO_KEEPALIVE, etc.
  * - Unix-specific options like SO_PEERCRED for retrieving peer process credentials
  */
trait UnixSockets[F[_]] {

  /** Returns a resource which opens a unix socket to the specified path.
    * @param address The unix socket address to connect to
    * @param options List of socket options to apply to the socket. Common options include:
    *               - SO_REUSEADDR: Allows reuse of local addresses
    *               - SO_KEEPALIVE: Enables TCP keepalive messages
    *               - SO_PEERCRED: Retrieves peer process credentials (Unix-specific)
    */
  def client(address: UnixSocketAddress, options: List[SocketOption] = Nil): Resource[F, Socket[F]]

  /** Listens to the specified path for connections and emits a `Socket` for each connection.
    *
    * Note: the path referred to by `address` must not exist or otherwise binding will fail
    * indicating the address is already in use. To force binding in such a case, pass `deleteIfExists = true`,
    * which will first delete the path.
    *
    * By default, the path is deleted when the server closes. To override this, pass `deleteOnClose = false`.
    *
    * @param address The unix socket address to listen on
    * @param deleteIfExists Whether to delete the socket file if it exists
    * @param deleteOnClose Whether to delete the socket file when the server closes
    * @param options List of socket options to apply to the server socket. Common options include:
    *               - SO_REUSEADDR: Allows reuse of local addresses
    *               - SO_KEEPALIVE: Enables TCP keepalive messages
    *               - SO_PEERCRED: Retrieves peer process credentials (Unix-specific)
    */
  def server(
      address: UnixSocketAddress,
      deleteIfExists: Boolean = false,
      deleteOnClose: Boolean = true,
      options: List[SocketOption] = Nil
  ): Stream[F, Socket[F]]
}

object UnixSockets extends UnixSocketsCompanionPlatform {
  def apply[F[_]](implicit F: UnixSockets[F]): UnixSockets[F] = F

  /** Socket option for getting the credentials of the peer process.
    * This is only supported on Unix platforms.
    *
    * The SO_PEERCRED option returns the credentials of the peer process connected via a Unix domain socket.
    * This includes:
    * - Process ID (pid)
    * - User ID (uid)
    * - Group ID (gid)
    *
    * This option is useful for security-sensitive applications that need to verify the identity
    * of the connecting process.
    *
    * Note: This option is not supported on all platforms:
    * - Native: Fully supported
    * - JDK: Supported via JNR
    * - JavaScript: Not supported (throws UnsupportedOperationException)
    */
  val SO_PEERCRED: SocketOption.Key[PeerCredentials] = new SocketOption.Key[PeerCredentials] {
    private[net] def native: Int = 0x1002 // SO_PEERCRED
    private[net] def fromNative(value: Int): PeerCredentials = {
      val pid = (value >> 32) & 0xFFFFFFFF
      val uid = (value >> 16) & 0xFFFF
      val gid = value & 0xFFFF
      PeerCredentials(pid.toInt, uid.toInt, gid.toInt)
    }
    private[net] def getJs(socket: fs2.io.internal.facade.net.Socket): PeerCredentials =
      throw new UnsupportedOperationException("SO_PEERCRED is not supported on Node.js")
    def get[F[_]](socket: Socket[F]): F[PeerCredentials] = socket.getOption(this)
  }
}

/** Credentials of a peer process connected via a Unix domain socket.
  *
  * @param pid Process ID of the peer process
  * @param uid User ID of the peer process
  * @param gid Group ID of the peer process
  */
case class PeerCredentials(pid: Int, uid: Int, gid: Int)
