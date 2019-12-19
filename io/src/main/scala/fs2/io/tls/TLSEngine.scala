package fs2
package io
package tls

import javax.net.ssl.{SSLEngine, SSLEngineResult}

import cats.Applicative
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.implicits._

trait TLSEngine[F[_]] {
  def beginHandshake: F[Unit]
  def stopWrap: F[Unit]
  def stopUnwrap: F[Unit]
  def wrap(data: Chunk[Byte], binding: TLSEngine.Binding[F]): F[Unit]
  def unwrap(data: Chunk[Byte], binding: TLSEngine.Binding[F]): F[Option[Chunk[Byte]]]
}

object TLSEngine {
  trait Binding[F[_]] {
    def write(data: Chunk[Byte]): F[Unit]
    def read: F[Option[Chunk[Byte]]]
  }

  def apply[F[_]: Concurrent: ContextShift](
      engine: SSLEngine,
      blocker: Blocker
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
      sslEngineTaskRunner = SSLEngineTaskRunner[F](engine, blocker)
    } yield new TLSEngine[F] {
      private def log(msg: String): F[Unit] =
        Sync[F].delay(println(s"\u001b[33m${msg}\u001b[0m"))

      def beginHandshake = Sync[F].delay(engine.beginHandshake())
      def stopWrap = Sync[F].delay(engine.closeOutbound())
      def stopUnwrap = Sync[F].delay(engine.closeInbound())

      def wrap(data: Chunk[Byte], binding: Binding[F]): F[Unit] =
        wrapBuffer.input(data) >> doWrap(binding)

      def doWrap(binding: Binding[F]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap { result =>
            log(s"doWrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                result.getHandshakeStatus match {
                  case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                    log("sending output chunk") >> wrapBuffer.output.flatMap { c =>
                      binding.write(c)
                    }
                  case other =>
                    log("sending output chunk") >> wrapBuffer.output.flatMap { c =>
                      binding.write(c)
                    } >>
                      doHandshake(other, true, binding) >> doWrap(binding)
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                ???
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doWrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def unwrap(data: Chunk[Byte], binding: Binding[F]): F[Option[Chunk[Byte]]] =
        unwrapBuffer.input(data) >> doUnwrap(binding)

      def doUnwrap(binding: Binding[F]): F[Option[Chunk[Byte]]] =
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
                    unwrapBuffer.output.map(Some(_))
                  case SSLEngineResult.HandshakeStatus.FINISHED =>
                    doUnwrap(binding)
                  case other =>
                    doHandshake(other, false, binding) >> doUnwrap(binding)
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                unwrapBuffer.output.map(Some(_))
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> doUnwrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def doHandshake(
          handshakeStatus: SSLEngineResult.HandshakeStatus,
          lastOperationWrap: Boolean,
          binding: Binding[F]
      ): F[Unit] =
        handshakeStatus match {
          case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING => ???
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
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              log(s"remaining: $remaining") >> {
                if (remaining > 0) doHsUnwrap(binding)
                else
                  binding.read.flatMap {
                    case Some(c) => unwrapBuffer.input(c) >> doHsUnwrap(binding)
                    case None    => ???
                  }
              }
            }
        }

      def doHsWrap(binding: Binding[F]): F[Unit] =
        wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap { result =>
            log(s"doHsWrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                wrapBuffer.output.flatMap(binding.write(_)) >> doHandshake(
                  result.getHandshakeStatus,
                  true,
                  binding
                )
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                ???
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doHsWrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def doHsUnwrap(binding: Binding[F]): F[Unit] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap { result =>
            log(s"doHsUnwrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                doHandshake(result.getHandshakeStatus, false, binding)
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                result.getHandshakeStatus match {
                  case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                    Applicative[F].unit
                  case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
                    binding.read.flatMap {
                        case Some(c) =>
                        unwrapBuffer.input(c) >> doHsUnwrap(binding)
                        case None => ???
                    }
                  case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
                    doHsWrap(binding)
                  case _ =>
                    sys.error("What to do here?")
                }
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> doHsUnwrap(binding)
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

        }

}
