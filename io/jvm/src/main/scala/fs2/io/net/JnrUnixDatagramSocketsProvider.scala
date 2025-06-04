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
package io
package net

import cats.effect.{Async, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all._
import cats.syntax.all._

import com.comcast.ip4s.{GenSocketAddress, IpAddress, MulticastJoin, UnixSocketAddress}

import fs2.io.file.Files

import java.nio.{Buffer, ByteBuffer}
import jnr.unixsocket.{UnixDatagramChannel, UnixSocketAddress => JnrUnixSocketAddress}

private[net] object JnrUnixDatagramSocketsProvider {

  lazy val supported: Boolean =
    try {
      Class.forName("jnr.unixsocket.UnixSocketChannel")
      true
    } catch {
      case _: ClassNotFoundException => false
    }

  def forAsyncAndFiles[F[_]: Async: Files]: UnixDatagramSocketsProvider[F] =
    new JnrUnixDatagramSocketsProvider[F]

  def forAsync[F[_]](implicit F: Async[F]): UnixDatagramSocketsProvider[F] =
    forAsyncAndFiles(F, Files.forAsync[F])
}

private[net] class JnrUnixDatagramSocketsProvider[F[_]](implicit F: Async[F], F2: Files[F])
    extends UnixDatagramSocketsProvider[F] {

  override def bindDatagramSocket(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, DatagramSocket[F]] = {
    val (filteredOptions, delete) = SocketOption.extractUnixSocketDeletes(options, address)
    // TODO use filtered options
    val _ = filteredOptions

    delete *> Resource
      .make(F.blocking(UnixDatagramChannel.open()))(ch => F.blocking(ch.close()))
      .evalTap { ch =>
        F.blocking(ch.bind(new JnrUnixSocketAddress(address.path)))
          .cancelable(F.blocking(ch.close()))
      }
      .evalMap { ch =>
        Mutex[F].map(ch -> _)
      }
      .map { case (ch, readMutex) =>
        val address0 = address
        new DatagramSocket[F] {
          private val readBuffer = ByteBuffer.allocate(65535)

          override val address = address0
          override def localAddress = F.delay(address.asIpUnsafe)

          override def supportedOptions = ???
          override def getOption[A](key: SocketOption.Key[A]) = ???
          override def setOption[A](key: SocketOption.Key[A], value: A) = ???

          private def withUnixSocketAddress[A](
              address: GenSocketAddress
          )(f: UnixSocketAddress => A): A =
            address match {
              case u: UnixSocketAddress => f(u)
              case _ =>
                throw new IllegalArgumentException(
                  s"Unsupported address type $address; must pass a UnixSocketAddress"
                )
            }

          private def blockingAndCloseIfCanceled[A](a: => A): F[A] =
            F.blocking(a).cancelable(F.blocking(ch.close()))

          override def connect(address: GenSocketAddress) =
            blockingAndCloseIfCanceled {
              withUnixSocketAddress(address) { u =>
                ch.connect(new JnrUnixSocketAddress(u.path))
                ()
              }
            }

          override def disconnect =
            blockingAndCloseIfCanceled {
              ch.disconnect()
              ()
            }

          override def read = readGen.map(_.toDatagram)

          override def readGen = readMutex.lock.surround {
            blockingAndCloseIfCanceled {
              val clientAddress = ch.receive(readBuffer)
              val read = readBuffer.position()
              val result =
                if (read == 0) Chunk.empty
                else {
                  val dest = new Array[Byte](read)
                  (readBuffer: Buffer).flip()
                  readBuffer.get(dest)
                  Chunk.array(dest)
                }
              (readBuffer: Buffer).clear()
              GenDatagram(UnixSocketAddress(clientAddress.path), result)
            }
          }

          override def reads = Stream.repeatEval(read)

          override def write(bytes: Chunk[Byte]) =
            blockingAndCloseIfCanceled {
              ch.send(
                bytes.toByteBuffer,
                ch.getRemoteSocketAddress
              ) // note: shouldn't be necessary to pass remote socket address but jnr throws an error otherwise
              ()
            }

          override def write(bytes: Chunk[Byte], address: GenSocketAddress) =
            blockingAndCloseIfCanceled {
              withUnixSocketAddress(address) { u =>
                val buffer = bytes.toByteBuffer
                val target = new JnrUnixSocketAddress(u.path)
                ch.send(buffer, target)
                ()
              }
            }

          override def write(datagram: Datagram) =
            F.raiseError(
              new UnsupportedOperationException(
                "Unix datagram socket does not support variant of write that takes a Datagram. Use variant that takes a GenSocketAddress or use connect & write(bytes)"
              )
            )

          override def writes = _.foreach(write)

          override def join(
              join: MulticastJoin[IpAddress],
              interface: DatagramSocket.NetworkInterface
          ) =
            F.raiseError(
              new UnsupportedOperationException("Multicast not supported on unix datagram sockets")
            )
        }
      }
  }
}
