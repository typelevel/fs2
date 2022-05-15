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
import scala.scalajs.js.annotation.JSImport

private[io] trait DuplexOptions {

  var autoDestroy: js.UndefOr[Boolean] = js.undefined

  var read: js.Function1[fs2.io.Readable, Unit] = null

  var write: js.Function4[fs2.io.Writable, js.typedarray.Uint8Array, String, js.Function1[
    js.Error,
    Unit
  ], Unit] = null

  var `final`: js.Function2[fs2.io.Duplex, js.Function1[
    js.Error,
    Unit
  ], Unit] = null

  var destroy: js.Function3[fs2.io.Duplex, js.Error, js.Function1[
    js.Error,
    Unit
  ], Unit] = null

}

@JSImport("stream", "Duplex")
@js.native
@nowarn
private[io] class Duplex(options: DuplexOptions) extends fs2.io.Duplex
