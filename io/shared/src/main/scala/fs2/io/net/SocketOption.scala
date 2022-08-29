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

package fs2.io.net

/** Specifies a socket option on a TCP/UDP socket.
  *
  * The companion provides methods for creating a socket option from each of the
  * JDK `java.net.StandardSocketOptions` as well as the ability to construct arbitrary
  * additional options. See the docs on `StandardSocketOptions` for details on each.
  */
sealed trait SocketOption {
  type Value
  val key: SocketOption.Key[Value]
  val value: Value
}

object SocketOption extends SocketOptionCompanionPlatform {
  def apply[A](key0: Key[A], value0: A): SocketOption = new SocketOption {
    type Value = A
    val key = key0
    val value = value0
  }
}
