package fs2
package io
package tls

import javax.net.ssl.{SSLEngine, SSLEngineResult, SSLSession}

import cats.Applicative
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.effect.concurrent.Semaphore
import cats.implicits._

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
  def wrap(data: Chunk[Byte], binding: TLSEngine.Binding[F]): F[Unit]
  def unwrap(data: Chunk[Byte], binding: TLSEngine.Binding[F]): F[Option[Chunk[Byte]]]
}

private[tls] object TLSEngine {
  trait Binding[F[_]] {
    def write(data: Chunk[Byte]): F[Unit]
    def read: F[Option[Chunk[Byte]]]
  }

  def apply[F[_]: Concurrent: ContextShift](
      engine: SSLEngine,
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
      wrapSem <- Semaphore[F](1)
      unwrapSem <- Semaphore[F](1)
      handshakeSem <- Semaphore[F](1)
      sslEngineTaskRunner = SSLEngineTaskRunner[F](engine, blocker)
    } yield new TLSEngine[F] {
      private def log(msg: String): F[Unit] =
        logger.map(_(msg)).getOrElse(Applicative[F].unit)

      def beginHandshake = Sync[F].delay(engine.beginHandshake())
      def session = Sync[F].delay(engine.getSession())
      def stopWrap = Sync[F].delay(engine.closeOutbound())
      def stopUnwrap = Sync[F].delay(engine.closeInbound()).attempt.void

      def wrap(data: Chunk[Byte], binding: Binding[F]): F[Unit] =
        wrapSem.withPermit(wrapBuffer.input(data) >> doWrap(binding))

      /**
        * Performs a wrap operation on the underlying engine.
        * Must be called with either the `wrapSem` and/or `handshakeSem`.
        */
      private def doWrap(binding: Binding[F]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap { result =>
            log(s"doWrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                doWrite(binding) >> {
                  result.getHandshakeStatus match {
                    case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                      Applicative[F].unit
                    case _ =>
                      handshake(result, true, binding) >> doWrap(binding)
                  }
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite(binding)
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doWrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }

      private def doWrite(binding: Binding[F]): F[Unit] = wrapBuffer.output.flatMap { out =>
        if (out.isEmpty) Applicative[F].unit
        else binding.write(out)
      }

      def unwrap(data: Chunk[Byte], binding: Binding[F]): F[Option[Chunk[Byte]]] =
        unwrapSem.withPermit(unwrapBuffer.input(data) >> doUnwrap(binding))

      /**
        * Performs an unwrap operation on the underlying engine.
        * Must be called with either the `unwrapSem` and/or `handshakeSem`.
        */
      private def doUnwrap(binding: Binding[F]): F[Option[Chunk[Byte]]] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap { result =>
            log(s"unwrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                result.getHandshakeStatus match {
                  case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                    dequeueUnwrap
                  case SSLEngineResult.HandshakeStatus.FINISHED =>
                    doUnwrap(binding)
                  case _ =>
                    handshake(result, false, binding) >> doUnwrap(binding)
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                dequeueUnwrap
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> doUnwrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap >> dequeueUnwrap
            }
          }

      private def dequeueUnwrap: F[Option[Chunk[Byte]]] =
        unwrapBuffer.output.map(out => if (out.isEmpty) None else Some(out))

      /** Performs a TLS handshake sequence, returning when the session has been esablished. */
      private def handshake(
          result: SSLEngineResult,
          lastOperationWrap: Boolean,
          binding: Binding[F]
      ): F[Unit] =
        handshakeSem.withPermit {
          stepHandshake(result, lastOperationWrap, binding)
        }

      /**
        * Determines what to do next given the result of a handshake operation.
        * Must be called with `handshakeSem`.
        */
      private def stepHandshake(
          result: SSLEngineResult,
          lastOperationWrap: Boolean,
          binding: Binding[F]
      ): F[Unit] =
        result.getHandshakeStatus match {
          case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
            Applicative[F].unit
          case SSLEngineResult.HandshakeStatus.FINISHED =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0) doHsUnwrap(binding)
              else Applicative[F].unit
            }
          case SSLEngineResult.HandshakeStatus.NEED_TASK =>
            sslEngineTaskRunner.runDelegatedTasks >> (if (lastOperationWrap) doHsWrap(binding)
                                                      else doHsUnwrap(binding))
          case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
            doHsWrap(binding)
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP | SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0 && result.getStatus != SSLEngineResult.Status.BUFFER_UNDERFLOW)
                doHsUnwrap(binding)
              else
                binding.read.flatMap {
                  case Some(c) => unwrapBuffer.input(c) >> doHsUnwrap(binding)
                  case None    => stopWrap >> stopUnwrap
                }
            }
        }

      /**
        * Performs a wrap operation as part of handshaking.
        * Must be called with `handshakeSem`.
        */
      private def doHsWrap(binding: Binding[F]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap { result =>
            log(s"doHsWrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK | SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                doWrite(binding) >> stepHandshake(
                  result,
                  true,
                  binding
                )
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doHsWrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }

      /**
        * Performs an unwrap operation as part of handshaking.
        * Must be called with `handshakeSem`.
        */
      private def doHsUnwrap(binding: Binding[F]): F[Unit] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap { result =>
            log(s"doHsUnwrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                stepHandshake(result, false, binding)
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                stepHandshake(result, false, binding)
              // binding.read.flatMap {
              //   case Some(c) => unwrapBuffer.input(c) >> doHsUnwrap(binding)
              //   case None    => stopWrap >> stopUnwrap
              // }
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> doHsUnwrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                stopWrap >> stopUnwrap
            }
          }
    }
}
