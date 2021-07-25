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
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import fs2.concurrent.SignallingRef
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.streamMod
import fs2.internal.jsdeps.node.tlsMod
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.EventEmitterOps._

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def forAsync[F[_]](
      socket: Socket[F],
      upgrade: streamMod.Duplex => tlsMod.TLSSocket
  )(implicit F: Async[F]): Resource[F, TLSSocket[F]] =
    for {
      (duplex, out) <- mkDuplex(socket.reads)
      _ <- out.through(socket.writes).compile.drain.background
      tlsSock = upgrade(duplex.asInstanceOf[streamMod.Duplex])
      dispatcher <- Dispatcher[F]
      sessionRef <- SignallingRef[F].of(Option.empty[SSLSession]).toResource
      _ <- registerListener[bufferMod.global.Buffer](tlsSock, nodeStrings.session)(
        _.on_session(_, _)
      ) { session =>
        dispatcher.unsafeRunAndForget(sessionRef.set(Some(session.toChunk)))
      }
      readSeamphore <- Semaphore[F](1).toResource
      readStream <- F
        .ref(readReadable(F.delay(tlsSock.asInstanceOf[Readable]), destroyIfNotEnded = false))
        .toResource
    } yield new AsyncTLSSocket(
      tlsSock,
      readSeamphore,
      readStream,
      sessionRef.discrete.unNone.take(1).compile.lastOrError
    )

  private[tls] final class AsyncTLSSocket[F[_]: Async](
      sock: tlsMod.TLSSocket,
      readSemaphore: Semaphore[F],
      readStream: Ref[F, Stream[F, Byte]],
      val session: F[SSLSession]
  ) extends Socket.AsyncSocket[F](sock.asInstanceOf[netMod.Socket], readSemaphore, readStream)
      with UnsealedTLSSocket[F]
}
