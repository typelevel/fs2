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
import fs2.concurrent.SignallingRef
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.eventsMod
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.streamMod
import fs2.internal.jsdeps.node.tlsMod
import fs2.internal.jsdeps.std
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.SuspendedStream

import scala.scalajs.js

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def forAsync[F[_]](
      socket: Socket[F],
      upgrade: streamMod.Duplex => tlsMod.TLSSocket
  )(implicit F: Async[F]): Resource[F, TLSSocket[F]] =
    for {
      dispatcher <- Dispatcher[F]
      duplexOut <- mkDuplex(socket.reads)
      (duplex, out) = duplexOut
      _ <- out.through(socket.writes).compile.drain.background
      sessionRef <- SignallingRef[F].of(Option.empty[SSLSession]).toResource
      sessionListener = { session =>
        dispatcher.unsafeRunAndForget(
          sessionRef.set(Some(new SSLSession(session.toChunk.toByteVector)))
        )
      }: js.Function1[bufferMod.global.Buffer, Unit]
      errorDef <- F.deferred[Throwable].toResource
      errorListener = { error =>
        val ex = js.JavaScriptException(error)
        dispatcher.unsafeRunAndForget(
          errorDef.complete(IOException.unapply(ex).getOrElse(ex))
        )
      }: js.Function1[std.Error, Unit]
      tlsSockReadable <- suspendReadableAndRead(
        destroyIfNotEnded = false,
        destroyIfCanceled = false
      ) {
        val tlsSock = upgrade(duplex.asInstanceOf[streamMod.Duplex])
        tlsSock.on_session(nodeStrings.session, sessionListener)
        tlsSock.asInstanceOf[netMod.Socket].on_error(nodeStrings.error, errorListener)
        tlsSock.asInstanceOf[Readable]
      }
        .flatMap { case tlsSockReadable @ (tlsSock, _) =>
          Resource.pure(tlsSockReadable).onFinalize {
            F.delay {
              val eventEmitter = tlsSock.asInstanceOf[eventsMod.EventEmitter]
              eventEmitter.removeListener(
                "session",
                sessionListener.asInstanceOf[js.Function1[Any, Unit]]
              )
              eventEmitter
                .removeListener("error", errorListener.asInstanceOf[js.Function1[Any, Unit]])
            }
          }
        }
      (tlsSock, readable) = tlsSockReadable
      readStream <- SuspendedStream(
        readable
      ).race(errorDef.get.flatMap(F.raiseError[SuspendedStream[F, Byte]]).toResource)
        .map(_.merge)
    } yield new AsyncTLSSocket(
      tlsSock.asInstanceOf[tlsMod.TLSSocket],
      readStream,
      sessionRef.discrete.unNone.head
        .concurrently(Stream.eval(errorDef.get.flatMap(F.raiseError[Unit])))
        .compile
        .lastOrError
    )

  private[tls] final class AsyncTLSSocket[F[_]: Async](
      sock: tlsMod.TLSSocket,
      readStream: SuspendedStream[F, Byte],
      val session: F[SSLSession]
  ) extends Socket.AsyncSocket[F](sock.asInstanceOf[netMod.Socket], readStream)
      with UnsealedTLSSocket[F]
}
