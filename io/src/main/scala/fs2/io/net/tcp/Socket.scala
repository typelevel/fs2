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
package tcp

import scala.concurrent.duration._

import com.comcast.ip4s.{IpAddress, SocketAddress}

/** Provides the ability to read/write from a TCP socket in the effect `F`.
  *
  * To construct a `Socket`, use the methods in the [[fs2.io.tcp]] package object.
  */
trait Socket[F[_]] {

  /** Reads up to `maxBytes` from the peer.
    *
    * Evaluates to None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
    * before returning even single byte. Otherwise returns Some(bytes) with bytes that were ready to be read.
    *
    * If `timeout` is specified, then resulting `F` will evaluate to failure with `java.nio.channels.InterruptedByTimeoutException`
    * if read was not satisfied in given timeout. Read is satisfied, when at least single Byte was received
    * before `timeout` expires.
    *
    * This may return None, as well when end of stream has been reached before timeout expired and no data
    * has been received.
    */
  def read(maxBytes: Int, timeout: Option[FiniteDuration] = None): F[Option[Chunk[Byte]]]

  /** Reads stream of bytes from this socket with `read` semantics. Terminates when eof is received.
    * On timeout, this fails with `java.nio.channels.InterruptedByTimeoutException`.
    */
  def reads(maxBytes: Int, timeout: Option[FiniteDuration] = None): Stream[F, Byte]

  /** Reads exactly `numBytes` from the peer in a single chunk.
    * If `timeout` is provided and no data arrives within the specified duration, then this results in
    * failure with `java.nio.channels.InterruptedByTimeoutException`.
    *
    * When returned size of bytes is < `numBytes` that indicates end-of-stream has been reached.
    */
  def readN(numBytes: Int, timeout: Option[FiniteDuration] = None): F[Option[Chunk[Byte]]]

  /** Indicates that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: F[Unit]

  /** Indicates to peer, we are done writing. * */
  def endOfOutput: F[Unit]

  def isOpen: F[Boolean]

  /** Closes the connection corresponding to this `Socket`. */
  def close: F[Unit]

  /** Asks for the remote address of the peer. */
  def remoteAddress: F[SocketAddress[IpAddress]]

  /** Asks for the local address of the socket. */
  def localAddress: F[SocketAddress[IpAddress]]

  /** Writes `bytes` to the peer. If `timeout` is provided
    * and the operation does not complete in the specified duration,
    * the returned `Process` fails with a `java.nio.channels.InterruptedByTimeoutException`.
    *
    * Completes when bytes are written to the socket.
    */
  def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration] = None): F[Unit]

  /** Writes the supplied stream of bytes to this socket via `write` semantics.
    */
  def writes(timeout: Option[FiniteDuration] = None): Pipe[F, Byte, INothing]
}
