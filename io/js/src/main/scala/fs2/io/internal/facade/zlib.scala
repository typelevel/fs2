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
import scala.scalajs.js.typedarray.Uint8Array

package object zlib {

  @js.native
  @JSImport("zlib", "createDeflate")
  private[io] def createDeflate(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "createDeflateRaw")
  private[io] def createDeflateRaw(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "deflateSync")
  private[io] def deflateSync(buffer: Uint8Array, options: Options): Uint8Array = js.native

  @js.native
  @JSImport("zlib", "createGzip")
  private[io] def createGzip(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "deflateRawSync")
  private[io] def deflateRawSync(buffer: Uint8Array, options: Options): Uint8Array = js.native

  @js.native
  @JSImport("zlib", "createGunzip")
  private[io] def createGunzip(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "inflateRawSync")
  private[io] def inflateRawSync(buffer: Uint8Array, options: Options): Uint8Array = js.native

  @js.native
  @JSImport("zlib", "createInflate")
  private[io] def createInflate(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "createInflateRaw")
  private[io] def createInflateRaw(options: Options): Zlib = js.native

  @js.native
  @JSImport("zlib", "inflateSync")
  private[io] def inflateSync(buffer: Uint8Array, options: Options): Uint8Array = js.native

  @js.native
  @JSImport("zlib", "gunzipSync")
  private[io] def gunzipSync(buffer: Uint8Array): Uint8Array = js.native

}

package zlib {

  @js.native
  private[io] trait Zlib extends fs2.io.Duplex {
    def close(cb: js.Function0[Unit]): Unit = js.native
  }

  @nowarn
  private[io] trait Options extends js.Object {

    var chunkSize: js.UndefOr[Int] = js.undefined

    var level: js.UndefOr[Int] = js.undefined

    var strategy: js.UndefOr[Int] = js.undefined

    var flush: js.UndefOr[Int] = js.undefined
  }

}
