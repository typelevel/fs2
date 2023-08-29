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

import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

import events.EventEmitter

@nowarn212("cat=unused")
private[io] object child_process {

  @js.native
  @JSImport("child_process", "spawn")
  def spawn(
      command: String,
      args: js.Array[String],
      options: SpawnOptions
  ): ChildProcess =
    js.native

  trait SpawnOptions extends js.Object {

    var cwd: js.UndefOr[String] = js.undefined

    var env: js.UndefOr[js.Dictionary[String]] = js.undefined

  }

  @js.native
  trait ChildProcess extends EventEmitter {

    def stdin: fs2.io.Writable = js.native

    def stdout: fs2.io.Readable = js.native

    def stderr: fs2.io.Readable = js.native

    // can also be `null`, so can't use `Int` ...
    def exitCode: js.Any = js.native

    def signalCode: String = js.native

    def kill(): Boolean

  }

}
