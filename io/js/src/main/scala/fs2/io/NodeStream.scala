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

package fs2.io

import fs2.io.internal.facade.events.EventEmitter
import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js

/** A facade for Node.js `stream.Readable`. Extend or cast to/from your own bindings.
  * @see [[https://nodejs.org/api/stream.html]]
  */
@js.native
@nowarn212("cat=unused")
trait Readable extends EventEmitter {

  protected[io] def read(): js.typedarray.Uint8Array = js.native

  protected[io] def destroy(): this.type = js.native

  protected[io] def push(chunk: js.typedarray.Uint8Array): Boolean = js.native

  protected[io] def readableEnded: Boolean = js.native

}

/** A facade for Node.js `stream.Writable`. Extend or cast to/from your own bindings.
  * @see [[https://nodejs.org/api/stream.html]]
  */
@js.native
@nowarn212("cat=unused")
trait Writable extends EventEmitter {

  protected[io] def destroy(): this.type = js.native

  protected[io] def write(
      chunk: js.typedarray.Uint8Array,
      cb: js.Function1[js.UndefOr[js.Error], Unit]
  ): Boolean = js.native

  protected[io] def end(cb: js.Function1[js.UndefOr[js.Error], Unit]): this.type = js.native

  protected[io] def writableEnded: Boolean = js.native

}

/** A facade for Node.js `stream.Duplex`. Extend or cast to/from your own bindings.
  * @see [[https://nodejs.org/api/stream.html]]
  */
@js.native
@nowarn212("cat=unused")
trait Duplex extends Readable with Writable {
  protected[io] override def destroy(): this.type = js.native
}

@deprecated("No longer raised", "3.9.3")
final class StreamDestroyedException private[io] () extends IOException
