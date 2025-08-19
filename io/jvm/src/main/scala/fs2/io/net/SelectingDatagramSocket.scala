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

import cats.effect.{LiftIO, Selector}
import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress, GenSocketAddress, NetworkInterface}
import fs2.{Chunk, Pipe, Stream}

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, SelectionKey}
import java.net.{NetworkInterface => JNetworkInterface}
import com.comcast.ip4s.MulticastJoin

private final class SelectingDatagramSocket[F[_]: LiftIO] private (
    selector: Selector,
    ch: DatagramChannel,
    readMutex: Mutex[F],
    writeMutex: Mutex[F],
    override val address: SocketAddress[IpAddress]
)(implicit F: Async[F])
    extends DatagramSocket[F] {

  private[this] val bufferSize = 1 << 16

  def localAddress: F[SocketAddress[IpAddress]] =
    F.pure(address)

  def read: F[Datagram] =
    readMutex.lock.surround {
      val buf = ByteBuffer.allocate(bufferSize)

      def go: F[Datagram] =
        F.delay(ch.receive(buf)).flatMap {
          case null =>
            selector.select(ch, SelectionKey.OP_READ).to *> go
          case src =>
            F.delay {
              buf.flip()
              val bytes = new Array[Byte](buf.remaining())
              buf.get(bytes)
              buf.clear()
              Datagram(
                SocketAddress.fromInetSocketAddress(src.asInstanceOf[InetSocketAddress]),
                Chunk.array(bytes)
              )
            }
        }
      go
    }

  def readGen: F[GenDatagram] =
    read.map(_.toGenDatagram)

  def reads: Stream[F, Datagram] =
    Stream.repeatEval(read)

  private def write0(bytes: Chunk[Byte], addr: Option[InetSocketAddress]): F[Unit] =
    writeMutex.lock.surround {
      val buf = bytes.toByteBuffer

      def go: F[Unit] =
        F.delay {
          addr match {
            case Some(a) => ch.send(buf, a)
            case None    => ch.write(buf)
          }
        }.flatMap { _ =>
          if (buf.hasRemaining) selector.select(ch, SelectionKey.OP_WRITE).to[F] *> go
          else F.unit
        }

      go
    }

  def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit] =
    write0(bytes, Some(address.asIpUnsafe.toInetSocketAddress))

  def write(bytes: Chunk[Byte]): F[Unit] =
    write0(bytes, None)

  def writes: Pipe[F, Datagram, Nothing] =
    _.evalMap(write).drain

  def connect(addr: GenSocketAddress): F[Unit] =
    F.delay(ch.connect(addr.asIpUnsafe.toInetSocketAddress)).void

  def disconnect: F[Unit] =
    F.delay(ch.disconnect()).void

  def getOption[A](key: java.net.SocketOption[A]): F[Option[A]] =
    F.delay(Option(ch.getOption(key)))

  def setOption[A](key: java.net.SocketOption[A], value: A): F[Unit] =
    F.delay(ch.setOption(key, value)).void

  override def join(
      join: MulticastJoin[IpAddress],
      interface: NetworkInterface
  ): F[GroupMembership] =
    F.delay {
      val jinterface = JNetworkInterface.getByName(interface.name)
      val membership = join.fold(
        j => ch.join(j.group.address.toInetAddress, jinterface),
        j => ch.join(j.group.address.toInetAddress, jinterface, j.source.toInetAddress)
      )
      new GroupMembership {
        def drop = F.delay(membership.drop)
        def block(source: IpAddress) =
          F.delay { membership.block(source.toInetAddress); () }
        def unblock(source: IpAddress) =
          F.delay { membership.unblock(source.toInetAddress); () }
        override def toString = "GroupMembership"
      }
    }

  override def join(
      j: MulticastJoin[IpAddress],
      interface: JNetworkInterface
  ): F[GroupMembership] =
    join(j, NetworkInterface.fromJava(interface))

  override def supportedOptions: F[Set[SocketOption.Key[?]]] =
    F.delay {
      ch.supportedOptions.asScala.toSet
    }

}

private object SelectingDatagramSocket {
  def apply[F[_]: LiftIO](
      selector: Selector,
      ch: DatagramChannel,
      local: SocketAddress[IpAddress]
  )(implicit F: Async[F]): F[DatagramSocket[F]] =
    (Mutex[F], Mutex[F]).flatMapN { (readM, writeM) =>
      F.delay {
        new SelectingDatagramSocket[F](selector, ch, readM, writeM, local)
      }
    }
}
