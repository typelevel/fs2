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

package fs2.io.internal

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import fs2.js.node.eventsMod

import scala.scalajs.js

private[fs2] object EventEmitterOps {

  def registerListener0[F[_]: Sync, E, V](emitter: E, event: V)(
      register: (E, V, js.Function0[Unit]) => Unit
  )(callback: js.Function0[Unit]): Resource[F, Unit] =
    Resource.make(Sync[F].delay(register(emitter, event, callback)))(_ =>
      Sync[F].delay(
        emitter
          .asInstanceOf[eventsMod.EventEmitter]
          .removeListener(
            event.asInstanceOf[String],
            callback.asInstanceOf[js.Function1[js.Any, Unit]]
          )
      )
    )

  def registerListener[A] = new RegisterListenerPartiallyApplied[A]

  final class RegisterListenerPartiallyApplied[A](private val dummy: Boolean = false)
      extends AnyVal {

    def apply[F[_]: Sync, E, V](emitter: E, event: V)(
        register: (E, V, js.Function1[A, Unit]) => Unit
    )(callback: js.Function1[A, Unit]): Resource[F, Unit] =
      Resource.make(Sync[F].delay(register(emitter, event, callback)))(_ =>
        Sync[F].delay(
          emitter
            .asInstanceOf[eventsMod.EventEmitter]
            .removeListener(
              event.asInstanceOf[String],
              callback.asInstanceOf[js.Function1[js.Any, Unit]]
            )
        )
      )
  }

}
