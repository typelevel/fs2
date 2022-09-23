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
package interop
package reactivestreams

import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import org.scalatestplus.testng._
import org.testng.annotations.AfterClass

import scala.annotation.nowarn

@nowarn
trait UnsafeTestNGSuite extends TestNGSuiteLike {

  protected var dispatcher: Dispatcher[IO] = _
  private var shutdownDispatcher: IO[Unit] = _

  private val mkDispatcher = Dispatcher[IO].allocated
  private val t = mkDispatcher.unsafeRunSync()
  dispatcher = t._1
  shutdownDispatcher = t._2

  @AfterClass
  def afterAll() = shutdownDispatcher.unsafeRunSync()
}
