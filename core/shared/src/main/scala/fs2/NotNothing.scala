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

/**
  * Witnesses that a type `A` is not the type `Nothing`.
  *
  * This is used to prevent operations like `flatMap` from being called
  * on a `Stream[F, Nothing]`. Doing so is typically incorrect as the
  * function passed to `flatMap` of shape `Nothing => Stream[F, B]` can
  * never be called.
  */
sealed trait NotNothing[-A]
object NotNothing {
  @annotation.implicitAmbiguous("Invalid type Nothing found in prohibited position")
  implicit val nothing1: NotNothing[Nothing] = new NotNothing[Nothing] {}
  implicit val nothing2: NotNothing[Nothing] = new NotNothing[Nothing] {}

  implicit def instance[A]: NotNothing[A] = new NotNothing[A] {}
}
