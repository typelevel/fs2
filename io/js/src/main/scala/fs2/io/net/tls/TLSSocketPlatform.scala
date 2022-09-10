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
import fs2.io.internal.facade
import fs2.io.internal.SuspendedStream
import scodec.bits.ByteVector

import scala.scalajs.js

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def forAsync[F[_]](
      socket: Socket[F],
      upgrade: fs2.io.Duplex => facade.tls.TLSSocket,
      dispatcher: Dispatcher[F]
  )(implicit F: Async[F]): Resource[F, TLSSocket[F]] =
    for {
      duplexOut <- mkDuplex(socket.reads)
      (duplex, out) = duplexOut
      _ <- out.through(socket.writes).compile.drain.background
      sessionRef <- SignallingRef[F].of(Option.empty[SSLSession]).toResource
      tlsSockReadable <- suspendReadableAndRead(
        destroyIfNotEnded = false,
        destroyIfCanceled = false
      ) {
        val tlsSock = upgrade(duplex)
        tlsSock.on[js.typedarray.Uint8Array](
          "session",
          session =>
            dispatcher.unsafeRunAndForget(
              sessionRef.set(Some(new SSLSession(ByteVector.view(session))))
            )
        )
        tlsSock
      }
      (tlsSock, readable) = tlsSockReadable
      _ <- Resource.unit[F].onFinalize(F.delay(tlsSock.removeAllListeners("session")))
      readStream <- SuspendedStream(readable)
    } yield new AsyncTLSSocket(
      tlsSock,
      readStream,
      sessionRef.discrete.unNone.head.compile.lastOrError,
      F.delay[Any](tlsSock.alpnProtocol).flatMap {
        case false            => "".pure // mimicking JVM
        case protocol: String => protocol.pure
        case _                => F.raiseError(new NoSuchElementException)
      }
    )

  private[tls] final class AsyncTLSSocket[F[_]: Async](
      sock: facade.tls.TLSSocket,
      readStream: SuspendedStream[F, Byte],
      val session: F[SSLSession],
      val applicationProtocol: F[String]
  ) extends Socket.AsyncSocket[F](sock, readStream)
      with UnsealedTLSSocket[F]
}
