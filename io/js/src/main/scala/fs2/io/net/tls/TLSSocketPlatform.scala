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
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.EventEmitterOps._
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.tlsMod
import fs2.internal.jsdeps.node.bufferMod

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def forAsync[F[_]](
      socket: Socket[F],
      mk: netMod.Socket => tlsMod.TLSSocket
  )(implicit F: Async[F]): Resource[F, TLSSocket[F]] =
    for {
      sock <- socket.underlying.toResource
      tlsSock <- Resource.make(for {
        tlsSock <- F.delay(mk(sock))
        _ <- socket.swap(tlsSock.asInstanceOf[netMod.Socket])
      } yield tlsSock)(_ => socket.swap(sock)) // Swap back when we're done
      dispatcher <- Dispatcher[F]
      deferredSession <- F.deferred[SSLSession].toResource
      _ <- registerListener[bufferMod.global.Buffer](tlsSock, nodeStrings.session)(
        _.on_session(_, _)
      ) { session =>
        dispatcher.unsafeRunAndForget(deferredSession.complete(session.toChunk))
      }
    } yield new AsyncTLSSocket(socket, deferredSession.get)

  private[tls] final class AsyncTLSSocket[F[_]](socket: Socket[F], val session: F[SSLSession])
      extends UnsealedTLSSocket[F] {

    override private[net] def underlying: F[netMod.Socket] = socket.underlying

    override private[net] def swap(nextSock: netMod.Socket): F[Unit] = socket.swap(nextSock)

    override def read(maxBytes: Int): F[Option[Chunk[Byte]]] = socket.read(maxBytes)

    override def readN(numBytes: Int): F[Chunk[Byte]] = socket.readN(numBytes)

    override def reads: Stream[F, Byte] = socket.reads

    override def endOfOutput: F[Unit] = socket.endOfOutput

    override def isOpen: F[Boolean] = socket.isOpen

    override def remoteAddress: F[SocketAddress[IpAddress]] = socket.remoteAddress

    override def localAddress: F[SocketAddress[IpAddress]] = socket.localAddress

    override def write(bytes: Chunk[Byte]): F[Unit] = socket.write(bytes)

    override def writes: Pipe[F, Byte, INothing] = socket.writes

  }
}
