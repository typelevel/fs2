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

package fs2.io.internal.facade

import scala.annotation.nowarn
import scala.scalajs.js
import cats.effect.std.Dispatcher
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._

@js.native
@nowarn
private[io] trait EventEmitter extends js.Object {

  protected[io] def on[E](eventName: String, listener: js.Function1[E, Unit]): this.type = js.native

  protected[io] def removeListener(
      eventName: String,
      listener: js.Function1[Nothing, Unit]
  ): this.type =
    js.native

}

private[io] object EventEmitter {
  implicit class ops(val eventTarget: EventEmitter) extends AnyVal {
    def registerListener[F[_], E](eventName: String, dispatcher: Dispatcher[F])(
        listener: E => F[Unit]
    )(implicit F: Sync[F]): Resource[F, Unit] = Resource
      .make(F.delay {
        val fn: js.Function1[E, Unit] = e => dispatcher.unsafeRunAndForget(listener(e))
        eventTarget.on(eventName, fn)
        fn
      })(fn => F.delay(eventTarget.removeListener(eventName, fn)))
      .void
  }
}
