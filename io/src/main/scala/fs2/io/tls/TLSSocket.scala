package fs2
package io
package tls

import scala.concurrent.duration._

import java.net.SocketAddress
import javax.net.ssl.SSLSession

import cats.Applicative
import cats.effect.concurrent.Semaphore
import cats.effect._
import cats.syntax.all._

import fs2.io.tcp.Socket

/**
  * TCP socket that supports encryption via TLS.
  *
  * To construct a `TLSSocket`, use the `client` and `server` methods on `TLSContext`.
  */
sealed trait TLSSocket[F[_]] extends Socket[F] {

  /** Initiates handshaking -- either the initial or a renegotiation. */
  def beginHandshake: F[Unit]

  /**
    * Provides access to the current `SSLSession` for purposes of querying
    * session info such as the negotiated cipher suite or the peer certificate.
    */
  def session: F[SSLSession]
}

object TLSSocket {

  private def binding[F[_]](
      socket: Socket[F],
      writeTimeout: Option[FiniteDuration] = None,
      readMaxBytes: Int = 16 * 1024
  ): TLSEngine.Binding[F] =
    new TLSEngine.Binding[F] {
      def write(data: Chunk[Byte]) = socket.write(data, writeTimeout)
      def read = socket.read(readMaxBytes, None)
    }

  private[tls] def apply[F[_]: Concurrent](
      socket: Socket[F],
      engine: TLSEngine[F]
  ): Resource[F, TLSSocket[F]] =
    Resource.make(mk(socket, engine))(_.close)

  private def mk[F[_]: Concurrent](
      socket: Socket[F],
      engine: TLSEngine[F]
  ): F[TLSSocket[F]] =
    for {
      readSem <- Semaphore(1)
    } yield new TLSSocket[F] {
      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        engine.wrap(bytes, binding(socket, writeTimeout = timeout))

      private def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        socket.read(maxBytes, timeout).flatMap {
          case Some(c) =>
            engine.unwrap(c, binding(socket)).flatMap {
              case Some(c) => Applicative[F].pure(Some(c))
              case None    => read0(maxBytes, timeout)
            }
          case None =>
            Applicative[F].pure(None)
        }

      def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSem.withPermit {
          def go(acc: Chunk.Queue[Byte]): F[Option[Chunk[Byte]]] = {
            val toRead = numBytes - acc.size
            if (toRead <= 0) Applicative[F].pure(Some(acc.toChunk))
            else
              read(numBytes, timeout).flatMap {
                case Some(chunk) => go(acc :+ chunk): F[Option[Chunk[Byte]]]
                case None        => Applicative[F].pure(Some(acc.toChunk)): F[Option[Chunk[Byte]]]
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

      def beginHandshake: F[Unit] =
        engine.beginHandshake

      def session: F[SSLSession] =
        engine.session

      def close: F[Unit] =
        engine.stopWrap >> engine.stopUnwrap >> socket.close

      def isOpen: F[Boolean] = socket.isOpen
    }
}
