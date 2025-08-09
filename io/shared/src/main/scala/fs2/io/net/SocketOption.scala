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

import cats.Applicative
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.UnixSocketAddress
import fs2.io.file.{Files, Path}

/** Specifies a socket option on a socket.
  *
  * The companion provides methods for creating various socket options.
  */
sealed trait SocketOption {
  type Value
  val key: SocketOption.Key[Value]
  val value: Value
}

object SocketOption extends SocketOptionCompanionPlatform {
  def apply[A](key0: Key[A], value0: A): SocketOption = new SocketOption {
    type Value = A
    println("ehelp")
    val key = key0
    val value = value0
  }

  private[net] def extractUnixSocketDeletes[F[_]: Applicative: Files](
      options: List[SocketOption],
      address: UnixSocketAddress
  ): (List[SocketOption], Resource[F, Unit]) = {
    var deleteIfExists: Boolean = false
    var deleteOnClose: Boolean = true

    val filteredOptions = options.filter { opt =>
      if (opt.key == SocketOption.UnixSocketDeleteIfExists) {
        deleteIfExists = opt.value.asInstanceOf[Boolean]
        false
      } else if (opt.key == SocketOption.UnixSocketDeleteOnClose) {
        deleteOnClose = opt.value.asInstanceOf[Boolean]
        false
      } else {
        true
      }
    }

    val delete = Resource.make {
      Files[F].deleteIfExists(Path(address.path)).whenA(deleteIfExists)
    } { _ =>
      Files[F].deleteIfExists(Path(address.path)).whenA(deleteOnClose)
    }

    (filteredOptions, delete)
  }
}
