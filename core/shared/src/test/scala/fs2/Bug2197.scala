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

import cats.effect.{IO, IOApp}

/** This should run forever, with each failed inner stream getting handled - no error should
  * ever make it to the outer handler.
  *
  * Instead, under CE2, the runtime exception escapes to the outer handler occassionally - within
  * a few seconds to a minute.  Under CE3, the program terminates successfully - which I think is
  * the result of the exception being raised in an effect finalizer somewhere.
  */
object Bug2197 extends IOApp.Simple {

  def run: IO[Unit] =
    Stream(Stream(()) ++ Stream.raiseError[IO](new RuntimeException)).repeat
      .flatMap { s =>
        s.prefetch.handleErrorWith(t => Stream.eval(IO.println(s"Inner stream failed: $t")))
      }
      .flatMap(_ => Stream.empty)
      .compile
      .drain
      .handleErrorWith(t => IO.println(s"Outer stream failed: $t"))
}
