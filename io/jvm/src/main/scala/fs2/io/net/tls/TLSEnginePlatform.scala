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
package tls

import javax.net.ssl.{SSLEngine, SSLEngineResult}

import cats.Applicative
import cats.effect.kernel.{Async, Sync}
import cats.effect.std.Semaphore
import cats.syntax.all._

trait TLSEnginePlatform { self: TLSEngine.type =>
  type SSLSession = javax.net.ssl.SSLSession

  def apply[F[_]: Async](
      engine: SSLEngine,
      binding: Binding[F],
      logger: TLSLogger[F]
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
      sslEngineTaskRunner = SSLEngineTaskRunner[F](engine)
    } yield new TLSEngine[F] {
      private val doLog: (() => String) => F[Unit] =
        logger match {
          case e: TLSLogger.Enabled[_] => msg => e.log(msg())
          case TLSLogger.Disabled      => _ => Applicative[F].unit
        }

      private def log(msg: => String): F[Unit] = doLog(() => msg)

      def beginHandshake = Sync[F].delay(engine.beginHandshake())
      def session = Sync[F].delay(engine.getSession())
      def applicationProtocol = Sync[F].delay(engine.getApplicationProtocol())
      def stopWrap = Sync[F].delay(engine.closeOutbound())
      def stopUnwrap = Sync[F].delay(engine.closeInbound()).attempt.void

      def write(data: Chunk[Byte]): F[Unit] =
        writeSemaphore.permit.use(_ => write0(data))

      private def write0(data: Chunk[Byte]): F[Unit] =
        wrapBuffer.input(data) >> wrap

      /** Performs a wrap operation on the underlying engine. */
      private def wrap: F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap(result => log(s"wrap result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                doWrite >> {
                  result.getHandshakeStatus match {
                    case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                      wrapBuffer.inputRemains
                        .flatMap(x => wrap.whenA(x > 0 && result.bytesConsumed > 0))
                    case _ =>
                      handshakeSemaphore.permit
                        .use(_ => stepHandshake(result, true)) >> wrap
                  }
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> wrap
              case SSLEngineResult.Status.CLOSED =>
                stopWrap
            }
          }

      private def doWrite: F[Unit] =
        wrapBuffer.output(Int.MaxValue).flatMap { out =>
          if (out.isEmpty) Applicative[F].unit
          else binding.write(out)
        }

      def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
        readSemaphore.permit.use(_ => read0(maxBytes))

      private def initialHandshakeDone: F[Boolean] =
        Sync[F].delay(engine.getSession.getCipherSuite != "SSL_NULL_WITH_NULL_NULL")

      private def read0(maxBytes: Int): F[Option[Chunk[Byte]]] =
        // Check if the initial handshake has finished -- if so, read; otherwise, handshake and then read
        unwrapThenTakeUnwrapped(maxBytes).flatMap { out =>
          if (out.isEmpty)
            initialHandshakeDone.ifM(
              read1(maxBytes),
              write(Chunk.empty) >> unwrapThenTakeUnwrapped(maxBytes).flatMap { out =>
                if (out.isEmpty) read1(maxBytes) else Applicative[F].pure(out)
              }
            )
          else Applicative[F].pure(out)
        }

      private def read1(maxBytes: Int): F[Option[Chunk[Byte]]] =
        binding.read(maxBytes.max(engine.getSession.getPacketBufferSize)).flatMap {
          case Some(c) =>
            unwrapBuffer.input(c) >> unwrap(maxBytes).flatMap {
              case s @ Some(_) => Applicative[F].pure(s)
              case None        => read1(maxBytes)
            }
          case None => Applicative[F].pure(None)
        }

      /** Performs an unwrap operation on the underlying engine. */
      private def unwrap(maxBytes: Int): F[Option[Chunk[Byte]]] =
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
                      .ifM(unwrap(maxBytes), takeUnwrapped(maxBytes))
                  case SSLEngineResult.HandshakeStatus.FINISHED =>
                    unwrap(maxBytes)
                  case _ =>
                    handshakeSemaphore.permit
                      .use(_ => stepHandshake(result, false)) >> unwrap(
                      maxBytes
                    )
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                takeUnwrapped(maxBytes)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> unwrap(maxBytes)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap >> takeUnwrapped(maxBytes)
            }
          }

      private def takeUnwrapped(maxBytes: Int): F[Option[Chunk[Byte]]] =
        unwrapBuffer.output(maxBytes).map(out => if (out.isEmpty) None else Some(out))

      private def unwrapThenTakeUnwrapped(maxBytes: Int): F[Option[Chunk[Byte]]] =
        unwrapBuffer.inputRemains.map(_ > 0).ifM(unwrap(maxBytes), takeUnwrapped(maxBytes))

      /** Determines what to do next given the result of a handshake operation.
        * Must be called with `handshakeSem`.
        */
      private def stepHandshake(
          result: SSLEngineResult,
          lastOperationWrap: Boolean
      ): F[Unit] =
        result.getHandshakeStatus match {
          case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
            Applicative[F].unit
          case SSLEngineResult.HandshakeStatus.FINISHED =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0) unwrapHandshake
              else Applicative[F].unit
            }
          case SSLEngineResult.HandshakeStatus.NEED_TASK =>
            sslEngineTaskRunner.runDelegatedTasks >> (if (lastOperationWrap) wrapHandshake
                                                      else unwrapHandshake)
          case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
            wrapHandshake
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0 && result.getStatus != SSLEngineResult.Status.BUFFER_UNDERFLOW)
                unwrapHandshake
              else
                binding.read(engine.getSession.getPacketBufferSize).flatMap {
                  case Some(c) => unwrapBuffer.input(c) >> unwrapHandshake
                  case None =>
                    unwrapBuffer.inputRemains.flatMap(x =>
                      if (x > 0) Applicative[F].unit else stopUnwrap
                    )
                }
            }
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN =>
            unwrapHandshake
        }

      /** Performs a wrap operation as part of handshaking. */
      private def wrapHandshake: F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap(result => log(s"wrapHandshake result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK | SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite >> stepHandshake(
                  result,
                  true
                )
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> wrapHandshake
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }

      /** Performs an unwrap operation as part of handshaking. */
      private def unwrapHandshake: F[Unit] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap(result => log(s"unwrapHandshake result: $result"))
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                stepHandshake(result, false)
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                stepHandshake(result, false)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> unwrapHandshake
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }
    }
}
