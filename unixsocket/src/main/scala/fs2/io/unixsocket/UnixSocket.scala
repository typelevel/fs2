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

package fs2.io.unixsocket

import cats.implicits._
import cats.effect._
import fs2._
import fs2.io.net.Socket
import jnr.unixsocket._ // This is where the fun begins
import jnr.unixsocket.impl.AbstractNativeSocketChannel
import java.nio.{Buffer, ByteBuffer}
import java.nio.file._
import cats.effect.std._
import java.net.InetSocketAddress

object UnixSocket {

  def client[F[_]: Async](address: UnixSocketAddress): Resource[F, Socket[F]] =
    Resource
      .eval(Sync[F].delay(UnixSocketChannel.open(address)))
      .flatMap(makeSocket[F](_))

  def server[F[_]](
      address: UnixSocketAddress
  )(implicit
      F: Async[F]
  ): Resource[F, Stream[F, Resource[F, Socket[F]]]] = {
    def setup = fs2.io.file
      .Files[F]
      .exists(Paths.get(address.path))
      .ifM(
        F.raiseError(
          new RuntimeException(
            "Socket Location Already Exists, Server cannot create Socket Location when location already exists."
          )
        ),
        F.blocking {
          val serverChannel = UnixServerSocketChannel.open()
          serverChannel.configureBlocking(false)
          val sock = serverChannel.socket()
          sock.bind(address)
          serverChannel
        }
      )

    def cleanup(sch: UnixServerSocketChannel): F[Unit] =
      F.blocking {
        if (sch.isOpen) sch.close()
        if (sch.isRegistered()) println("Server Still Registered")
      }

    def acceptIncoming(sch: UnixServerSocketChannel): Stream[F, Resource[F, Socket[F]]] = {
      def go: Stream[F, Resource[F, Socket[F]]] = {
        def acceptChannel: F[UnixSocketChannel] =
          F.blocking {
            val ch = sch.accept()
            ch.configureBlocking(false)
            ch
          }

        Stream.eval(acceptChannel.attempt).flatMap {
          case Left(_)         => Stream.empty[F]
          case Right(accepted) => Stream.emit(makeSocket(accepted))
        } ++ go
      }
      go
    }

    Resource.make(setup)(cleanup).map { sch =>
      acceptIncoming(sch)
    }
  }

  private def makeSocket[F[_]](
      ch: AbstractNativeSocketChannel
  )(implicit F: Async[F]): Resource[F, Socket[F]] = {
    val socket = (Semaphore[F](1), Semaphore[F](1), Ref[F].of(ByteBuffer.allocate(0))).mapN {
      (readSemaphore, writeSemaphore, bufferRef) =>
        // Reads data to remaining capacity of supplied ByteBuffer
        def readChunk(buff: ByteBuffer): F[Int] =
          F.blocking(ch.read(buff))

        // gets buffer of desired capacity, ready for the first read operation
        // If the buffer does not have desired capacity it is resized (recreated)
        // buffer is also reset to be ready to be written into.
        def getBufferOf(sz: Int): F[ByteBuffer] =
          bufferRef.get.flatMap { buff =>
            if (buff.capacity() < sz)
              F.delay(ByteBuffer.allocate(sz)).flatTap(bufferRef.set)
            else
              F.delay {
                (buff: Buffer).clear()
                (buff: Buffer).limit(sz)
                buff
              }
          }

        // When the read operation is done, this will read up to buffer's position bytes from the buffer
        // this expects the buffer's position to be at bytes read + 1
        def releaseBuffer(buff: ByteBuffer): F[Chunk[Byte]] =
          F.delay {
            val read = buff.position()
            val result =
              if (read == 0) Chunk.empty
              else {
                val dest = new Array[Byte](read)
                (buff: Buffer).flip()
                buff.get(dest)
                Chunk.array(dest)
              }
            (buff: Buffer).clear()
            result
          }

        def read0(max: Int): F[Option[Chunk[Byte]]] = {
          val action = readSemaphore.permit.use { _ =>
            getBufferOf(max).flatMap { buff =>
              readChunk(buff).flatMap { case read =>
                if (read < 0) F.pure(Option.empty[Chunk[Byte]])
                else releaseBuffer(buff).map(_.some)
              }
            }
          }
          action
        }

        def readN0(max: Int): F[Chunk[Byte]] = {
          val action = readSemaphore.permit.use { _ =>
            getBufferOf(max).flatMap { buff =>
              def internalAction: F[Chunk[Byte]] =
                readChunk(buff).flatMap { case readBytes =>
                  if (readBytes < 0 || buff.position() >= max)
                    // read is done
                    releaseBuffer(buff)
                  else internalAction
                }

              internalAction
            }
          }
          action
        }

        def write0(bytes: Chunk[Byte]): F[Unit] = {
          def go(buff: ByteBuffer): F[Unit] =
            F.blocking(ch.write(buff)) >> {
              if (buff.remaining <= 0) F.unit
              else go(buff)
            }
          val action = writeSemaphore.permit.use { _ =>
            go(bytes.toByteBuffer)
          }

          action
        }

        ////////////

        new Socket[F] {
          private[this] final val defaultReadSize = 8192
          def readN(numBytes: Int): F[Chunk[Byte]] =
            readN0(numBytes)
          def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
            read0(maxBytes)
          def reads: Stream[F, Byte] =
            Stream.eval(read(defaultReadSize)).flatMap {
              case Some(bytes) =>
                Stream.chunk(bytes) ++ reads
              case None => Stream.empty
            }

          def write(bytes: Chunk[Byte]): F[Unit] =
            write0(bytes)
          def writes: Pipe[F, Byte, INothing] =
            _.chunks.flatMap(bs => Stream.eval(write(bs))).drain

          // TODO Someone check me on this
          import com.comcast.ip4s.{IpAddress => IpAddress4s, SocketAddress => SocketAddress4s}
          def localAddress: F[SocketAddress4s[IpAddress4s]] =
            F.blocking(
              SocketAddress4s.fromInetSocketAddress(
                ch.getLocalAddress.asInstanceOf[InetSocketAddress]
              )
            )
          def remoteAddress: F[SocketAddress4s[IpAddress4s]] =
            F.blocking(
              SocketAddress4s.fromInetSocketAddress(
                ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
              )
            )

          def isOpen: F[Boolean] = F.blocking(ch.isOpen)
          def close: F[Unit] = F.blocking(ch.close())
          def endOfOutput: F[Unit] =
            F.blocking {
              ch.shutdownOutput(); ()
            }
          def endOfInput: F[Unit] =
            F.blocking {
              ch.shutdownInput(); ()
            }
        }
    }
    Resource.make(socket)(_ => F.blocking(if (ch.isOpen) ch.close else ()).attempt.void)
  }

}
