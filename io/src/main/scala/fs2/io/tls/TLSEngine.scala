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
package tls

import scala.concurrent.duration.FiniteDuration

import javax.net.ssl.{SSLEngine, SSLEngineResult, SSLSession}

import cats.Applicative
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.effect.concurrent.Semaphore
import cats.syntax.all._

/**
  * Provides the ability to establish and communicate over a TLS session.
  *
  * This is a functional wrapper of the JDK `SSLEngine`.
  */
private[tls] trait TLSEngine[F[_]] {
  def beginHandshake: F[Unit]
  def session: F[SSLSession]
  def stopWrap: F[Unit]
  def stopUnwrap: F[Unit]
  def write(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit]
  def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]]
}

private[tls] object TLSEngine {
  trait Binding[F[_]] {
    def write(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit]
    def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]]
  }

  def apply[F[_]: Concurrent: ContextShift](
      engine: SSLEngine,
      binding: Binding[F],
      blocker: Blocker,
      logger: Option[String => F[Unit]] = None
  ): F[TLSEngine[F]] =
    for {
      wrapBuffer <- InputOutputBuffer[F](
        engine.getSession.getApplicationBufferSize,
        engine.getSession.getPacketBufferSize
      )
      unwrapBuffer <- InputOutputBuffer[F](
        engine.getSession.getPacketBufferSize,
        engine.getSession.getApplicationBufferSize
      )
      readSemaphore <- Semaphore[F](1)
      writeSemaphore <- Semaphore[F](1)
      handshakeSemaphore <- Semaphore[F](1)
      sslEngineTaskRunner = SSLEngineTaskRunner[F](engine, blocker)
    } yield new TLSEngine[F] {
      private def log(msg: String): F[Unit] =
        logger.map(_(msg)).getOrElse(Applicative[F].unit)

      def beginHandshake = Sync[F].delay(engine.beginHandshake())
      def session = Sync[F].delay(engine.getSession())
      def getApplicationProtocol = Sync[F].delay(engine.getApplicationProtocol())
      def stopWrap = Sync[F].delay(engine.closeOutbound())
      def stopUnwrap = Sync[F].delay(engine.closeInbound()).attempt.void

      def write(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        writeSemaphore.withPermit(write0(data, timeout))

      private def write0(data: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        wrapBuffer.input(data) >> wrap(timeout)

      /** Performs a wrap operation on the underlying engine. */
      private def wrap(timeout: Option[FiniteDuration]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap(result => log(s"wrap result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                doWrite(timeout) >> {
                  result.getHandshakeStatus match {
                    case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                      wrapBuffer.inputRemains
                        .flatMap(x => wrap(timeout).whenA(x > 0 && result.bytesConsumed > 0))
                    case _ =>
                      handshakeSemaphore
                        .withPermit(stepHandshake(result, true, timeout)) >> wrap(timeout)
                  }
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite(timeout)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> wrap(timeout)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }

      private def doWrite(timeout: Option[FiniteDuration]): F[Unit] =
        wrapBuffer.output(Int.MaxValue).flatMap { out =>
          if (out.isEmpty) Applicative[F].unit
          else binding.write(out, timeout)
        }

      def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSemaphore.withPermit(read0(maxBytes, timeout))

      private def initialHandshakeDone: F[Boolean] =
        Sync[F].delay(engine.getSession.getCipherSuite != "SSL_NULL_WITH_NULL_NULL")

      private def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        // Check if the initial handshake has finished -- if so, read; otherwise, handshake and then read
        initialHandshakeDone.ifM(
          dequeueUnwrap(maxBytes).flatMap { out =>
            if (out.isEmpty) read1(maxBytes, timeout) else Applicative[F].pure(out)
          },
          write(Chunk.empty, None) >> read1(maxBytes, timeout)
        )

      private def read1(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        binding.read(maxBytes.max(engine.getSession.getPacketBufferSize), timeout).flatMap {
          case Some(c) =>
            unwrapBuffer.input(c) >> unwrap(maxBytes, timeout).flatMap {
              case s @ Some(_) => Applicative[F].pure(s)
              case None        => read1(maxBytes, timeout)
            }
          case None => Applicative[F].pure(None)
        }

      /** Performs an unwrap operation on the underlying engine. */
      private def unwrap(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap(result => log(s"unwrap result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                result.getHandshakeStatus match {
                  case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                    unwrapBuffer.inputRemains
                      .map(_ > 0 && result.bytesConsumed > 0)
                      .ifM(unwrap(maxBytes, timeout), dequeueUnwrap(maxBytes))
                  case SSLEngineResult.HandshakeStatus.FINISHED =>
                    unwrap(maxBytes, timeout)
                  case _ =>
                    handshakeSemaphore
                      .withPermit(stepHandshake(result, false, timeout)) >> unwrap(
                      maxBytes,
                      timeout
                    )
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                dequeueUnwrap(maxBytes)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> unwrap(maxBytes, timeout)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap >> dequeueUnwrap(maxBytes)
            }
          }

      private def dequeueUnwrap(maxBytes: Int): F[Option[Chunk[Byte]]] =
        unwrapBuffer.output(maxBytes).map(out => if (out.isEmpty) None else Some(out))

      /**
        * Determines what to do next given the result of a handshake operation.
        * Must be called with `handshakeSem`.
        */
      private def stepHandshake(
          result: SSLEngineResult,
          lastOperationWrap: Boolean,
          timeout: Option[FiniteDuration]
      ): F[Unit] =
        result.getHandshakeStatus match {
          case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
            Applicative[F].unit
          case SSLEngineResult.HandshakeStatus.FINISHED =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0) unwrapHandshake(timeout)
              else Applicative[F].unit
            }
          case SSLEngineResult.HandshakeStatus.NEED_TASK =>
            sslEngineTaskRunner.runDelegatedTasks >> (if (lastOperationWrap) wrapHandshake(timeout)
                                                      else unwrapHandshake(timeout))
          case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
            wrapHandshake(timeout)
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0 && result.getStatus != SSLEngineResult.Status.BUFFER_UNDERFLOW)
                unwrapHandshake(timeout)
              else
                binding.read(engine.getSession.getPacketBufferSize, timeout).flatMap {
                  case Some(c) => unwrapBuffer.input(c) >> unwrapHandshake(timeout)
                  case None    => stopWrap >> stopUnwrap
                }
            }
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN =>
            unwrapHandshake(timeout)
        }

      /** Performs a wrap operation as part of handshaking. */
      private def wrapHandshake(timeout: Option[FiniteDuration]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap(result => log(s"wrapHandshake result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK | SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite(timeout) >> stepHandshake(
                  result,
                  true,
                  timeout
                )
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> wrapHandshake(timeout)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }

      /** Performs an unwrap operation as part of handshaking. */
      private def unwrapHandshake(timeout: Option[FiniteDuration]): F[Unit] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap(result => log(s"unwrapHandshake result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                stepHandshake(result, false, timeout)
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                stepHandshake(result, false, timeout)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> unwrapHandshake(timeout)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }
    }
}
