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

import com.comcast.ip4s.{IpAddress, SocketAddress}

/** Provides the ability to read/write from a TCP socket in the effect `F`.
  */
trait Socket[F[_]] {

  /** Reads up to `maxBytes` from the peer.
    *
    * Returns `None` if the "end of stream" is reached, indicating there will be no more bytes sent.
    */
  def read(maxBytes: Int): F[Option[Chunk[Byte]]]

  /** Reads exactly `numBytes` from the peer in a single chunk.
    *
    * Returns a chunk with size < `numBytes` upon reaching the end of the stream.
    */
  def readN(numBytes: Int): F[Chunk[Byte]]

  /** Reads bytes from the socket as a stream. */
  def reads: Stream[F, Byte]

  /** Indicates that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: F[Unit]

  /** Indicates to peer, we are done writing. * */
  def endOfOutput: F[Unit]

  def isOpen: F[Boolean]

  /** Asks for the remote address of the peer. */
  def remoteAddress: F[SocketAddress[IpAddress]]

  /** Asks for the local address of the socket. */
  def localAddress: F[SocketAddress[IpAddress]]

  /** Writes `bytes` to the peer.
    *
    * Completes when the bytes are written to the socket.
    */
  def write(bytes: Chunk[Byte]): F[Unit]

  /** Writes the supplied stream of bytes to this socket via `write` semantics.
    */
  def writes: Pipe[F, Byte, INothing]
}

object Socket extends SocketPlatform
