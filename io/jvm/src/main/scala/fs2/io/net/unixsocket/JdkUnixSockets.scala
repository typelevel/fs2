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

package fs2.io.net.unixsocket

import cats.effect.kernel.{Async, Resource}
import cats.effect.syntax.all._
import fs2.io.file.Files
import fs2.io.evalOnVirtualThreadIfAvailable
import fs2.io.net.SocketOption
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import jnr.unixsocket.{UnixSocket => JnrUnixSocket}

object JdkUnixSockets {

  def supported: Boolean = StandardProtocolFamily.values.size > 2

  def forAsyncAndFiles[F[_]: Async: Files]: UnixSockets[F] =
    new JdkUnixSocketsImpl[F]

  def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    forAsyncAndFiles(F, Files.forAsync[F])
}

private[unixsocket] class JdkUnixSocketsImpl[F[_]: Files](implicit F: Async[F])
    extends UnixSockets.AsyncUnixSockets[F] {
  
  protected def openChannel(address: UnixSocketAddress, options: List[SocketOption]) =
    evalOnVirtualThreadIfAvailable(
      Resource
        .make(
          F.blocking(SocketChannel.open(StandardProtocolFamily.UNIX))
        )(ch => F.blocking(ch.close()))
        .evalTap { ch =>
          options.traverse_(opt => F.blocking(opt.apply(ch.socket())))
        }
        .evalTap { ch =>
          F.blocking(ch.connect(UnixDomainSocketAddress.of(address.path)))
            .cancelable(F.blocking(ch.close()))
        }
    )

  protected def openServerChannel(address: UnixSocketAddress, options: List[SocketOption]) =
    evalOnVirtualThreadIfAvailable(
      Resource
        .make(
          F.blocking(ServerSocketChannel.open(StandardProtocolFamily.UNIX))
        )(ch => F.blocking(ch.close()))
        .evalTap { sch =>
          options.traverse_(opt => F.blocking(opt.apply(sch.socket())))
        }
        .evalTap { sch =>
          F.blocking(sch.bind(UnixDomainSocketAddress.of(address.path)))
            .cancelable(F.blocking(sch.close()))
        }
        .map { sch =>
          Resource.makeFull[F, SocketChannel] { poll =>
            poll(F.blocking(sch.accept).cancelable(F.blocking(sch.close())))
          }(ch => F.blocking(ch.close()))
        }
    )

  protected def getOption[A](socket: SocketChannel, option: SocketOption.Key[A]): F[A] = F.blocking {
    option match {
      case UnixSockets.SO_PEERCRED =>
        val jnrSocket = socket.socket().asInstanceOf[JnrUnixSocket]
        val pid = jnrSocket.getPeerPid()
        val uid = jnrSocket.getPeerUid()
        val gid = jnrSocket.getPeerGid()
        val value = (pid.toLong << 32) | (uid.toLong << 16) | gid.toLong
        option.fromNative(value.toInt)
      case _ =>
        socket.socket().getOption(option)
    }
  }

}
