package fs2
package io
package tcp

import scala.concurrent.duration._

import java.net.SocketAddress

import cats.Applicative
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.syntax.all._

import fs2.io.tls._

trait TLSSocket[F[_]] extends Socket[F] {

  /** Initiates a new TLS handshake. */
  def startHandshake: F[Unit]
}

object TLSSocket {

  private def binding[F[_]](socket: Socket[F], writeTimeout: Option[FiniteDuration] = None, readMaxBytes: Int = 16 * 1024, readTimeout: Option[FiniteDuration] = None): TLSEngine.Binding[F] =
    new TLSEngine.Binding[F] {
      def write(data: Chunk[Byte]) = socket.write(data, writeTimeout)
      def read = socket.read(readMaxBytes, readTimeout)
    }

  def apply[F[_]: Concurrent: ContextShift](
      socket: Socket[F],
      engine: TLSEngine[F]
  ): F[TLSSocket[F]] = for {
    readSem <- Semaphore(1)
  } yield new TLSSocket[F] {
      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        engine.wrap(bytes, binding(socket, writeTimeout = timeout))

      private def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        socket.read(maxBytes, timeout).flatMap {
          case Some(c) =>
            engine.unwrap(c, binding(socket, readTimeout = timeout))
          case None =>
            Applicative[F].pure(None)
        }

      def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSem.withPermit {
          def go(acc: Chunk.Queue[Byte]): F[Option[Chunk[Byte]]] = {
            val toRead = numBytes - acc.size
            if (toRead <= 0) Applicative[F].pure(Some(acc.toChunk))
            else {
              read(numBytes, timeout).flatMap {
                case Some(chunk) => go(acc :+ chunk): F[Option[Chunk[Byte]]]
                case None        => Applicative[F].pure(Some(acc.toChunk)): F[Option[Chunk[Byte]]]
              }
            }
          }
          go(Chunk.Queue.empty)
        }

      def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSem.withPermit(read0(maxBytes, timeout))

      def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
        Stream.repeatEval(read(maxBytes, timeout)).unNoneTerminate.flatMap(Stream.chunk)

      def writes(timeout: Option[FiniteDuration]): Pipe[F, Byte, Unit] =
        _.chunks.evalMap(write(_, timeout))

      def endOfOutput: F[Unit] =
        engine.stopWrap >> socket.endOfOutput

      def endOfInput: F[Unit] =
        engine.stopUnwrap >> socket.endOfInput

      def localAddress: F[SocketAddress] =
        socket.localAddress

      def remoteAddress: F[SocketAddress] =
        socket.remoteAddress

      def startHandshake: F[Unit] =
        Sync[F].delay(engine.beginHandshake)

      def close: F[Unit] =
        engine.stopWrap >> engine.stopUnwrap >> socket.close

      def isOpen: F[Boolean] = socket.isOpen
    }
}
