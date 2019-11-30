package fs2
package io
package tls

import scala.util.control.NonFatal

import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.{HandshakeStatus, Status}

import cats.Monad
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.implicits._

/**
  * Simple interface for `UNWRAP` operations.
  */
private[tls] trait Unwrap[F[_]] {
  /**
    * Performs unwrapping of the data received from the network.
    * @param data Data to be unwrapped
    * @return
    */
  def unwrap(data: Chunk[Byte]): F[Unwrap.Result]

  /**
    * During, and only during and handshake this is consulted to perform wrap operation.
    * Note that this works with different set of buffers that `wrap` and `unwrap`.
    *
    * Yields to result of the handshake, that contains bytes to be sent or signaling finish of the handshake.
    * @return
    */
  def wrapHandshake: F[Unwrap.HandshakeResult]
}

private[tls] object Unwrap {
  def apply[F[_]: Concurrent: ContextShift](sslEngine: SSLEngine, blocker: Blocker): F[Unwrap[F]] =
    for {
      ioBuff <- InputOutputBuffer[F](
        sslEngine.getSession.getPacketBufferSize,
        sslEngine.getSession.getApplicationBufferSize
      )
      ioHsBuff <- InputOutputBuffer[F](0, sslEngine.getSession.getApplicationBufferSize)
      sslEngineTaskRunner = SSLEngineTaskRunner[F](sslEngine, blocker)
    } yield new Unwrap[F] {
      def unwrap(data: Chunk[Byte]) =
        ioBuff.input(data) >> Unwrap.unwrap(ioBuff, sslEngine, sslEngineTaskRunner)

      def wrapHandshake =
        ioHsBuff.input(Chunk.empty) >> Unwrap.wrap(ioHsBuff, sslEngine, sslEngineTaskRunner)
    }

  private def unwrap[F[_]: Monad](
      ioBuff: InputOutputBuffer[F],
      sslEngine: SSLEngine,
      sslEngineTaskRunner: SSLEngineTaskRunner[F]
  ): F[Result] =
    ioBuff
      .perform {
        case (inBuffer, outBuffer) =>
          try {
            Right(sslEngine.unwrap(inBuffer, outBuffer))
          } catch { case NonFatal(err) => Left(err) }
      }
      .flatMap { result =>
        result.getStatus match {
          case Status.OK =>
            result.getHandshakeStatus match {
              case HandshakeStatus.NOT_HANDSHAKING =>
                // engine always return only one frame as part of unwrap.
                // however in buffer there may be more TLS frames, so in that case
                // we will consume data from both frames
                ioBuff.inputRemains.flatMap { remains =>
                  if (remains <= 0) ioBuff.output.map { appData =>
                    Result(
                      appData,
                      closed = false,
                      needWrap = false,
                      finished = false,
                      handshaking = false
                    )
                  } else unwrap(ioBuff, sslEngine, sslEngineTaskRunner)
                }

              case HandshakeStatus.NEED_WRAP =>
                // indicates that next operation needs to produce data,
                // and switch control to `handshakeWrap`.
                // this will release control to `wrap` function for handshake after acquiring the `wrap` lock
                ioBuff.output.map { appData =>
                  Result(
                    appData,
                    closed = false,
                    needWrap = true,
                    finished = false,
                    handshaking = true
                  )
                }

              case HandshakeStatus.NEED_UNWRAP =>
                // during handshake SSL Engine may require multiple unwraps to be performed
                // as a result we just leave the buffers as is and perform another unwrap
                // until we will get different result than UNWRAP
                // however exit with unwrap, if there was no data consumed, that indicates more data needs to be
                // read from the network

                if (result.bytesConsumed() != 0) unwrap(ioBuff, sslEngine, sslEngineTaskRunner)
                else
                  ioBuff.output.map { appData =>
                    Result(
                      appData,
                      closed = false,
                      needWrap = false,
                      finished = false,
                      handshaking = true
                    )
                  }

              case HandshakeStatus.NEED_TASK =>
                // Engine requires asynchronous tasks to be run, we will wait till they are run
                // to perform wrap again.
                // runTasks synchronization relies on SSL Engine.
                sslEngineTaskRunner.runDelegatedTasks >> unwrap(
                  ioBuff,
                  sslEngine,
                  sslEngineTaskRunner
                )

              case HandshakeStatus.FINISHED =>
                // handshake has been finished.
                // needs to be signalled as likely any wrap lock (and handshake success callback) may need to be released
                ioBuff.output.map { appData =>
                  Result(
                    appData,
                    closed = false,
                    needWrap = false,
                    finished = true,
                    handshaking = true
                  )
                }
            }

          case Status.BUFFER_OVERFLOW =>
            // need more spec for application data
            // expand app buffer and retry
            ioBuff.expandOutput >> unwrap(ioBuff, sslEngine, sslEngineTaskRunner)

          case Status.BUFFER_UNDERFLOW =>
            // we don't have enough data to form TLS Record
            // this indicates that more data has to be received before we will move on
            // as such just return result with no output
            // however during
            ioBuff.output.map { appData =>
              Result(
                appData,
                closed = false,
                needWrap = false,
                finished = false,
                handshaking = result.getHandshakeStatus != HandshakeStatus.NOT_HANDSHAKING
              )
            }

          case Status.CLOSED =>
            ioBuff.output.map { appData =>
              Result(
                appData,
                closed = true,
                needWrap = false,
                finished = false,
                handshaking = result.getHandshakeStatus != HandshakeStatus.NOT_HANDSHAKING
              )
            }
        }
      }

  private def wrap[F[_]: Sync](
      ioBuff: InputOutputBuffer[F],
      sslEngine: SSLEngine,
      sslEngineTaskRunner: SSLEngineTaskRunner[F]
  ): F[HandshakeResult] =
    ioBuff
      .perform {
        case (inBuffer, outBuffer) =>
          try {
            Right(sslEngine.wrap(inBuffer, outBuffer))
          } catch {
            case NonFatal(err) => Left(err)
          }
      }
      .flatMap { result =>
        result.getStatus match {
          case Status.OK =>
            result.getHandshakeStatus match {
              case HandshakeStatus.NOT_HANDSHAKING =>
                // impossible during handshake
                Sync[F].raiseError(
                  new RuntimeException(
                    "bug: NOT_HANDSHAKING in HANDSHAKE. Handshake must be terminated with FINISHED"
                  )
                )

              case HandshakeStatus.NEED_WRAP =>
                // requires more wrap operations before this can exist.
                // we may safely recurse
                // do not recurse if no bytes was produced.
                if (result.bytesProduced() != 0) wrap(ioBuff, sslEngine, sslEngineTaskRunner)
                else
                  ioBuff.output.map { send =>
                    HandshakeResult(send, closed = false, finished = false)
                  }

              case HandshakeStatus.NEED_UNWRAP =>
                // indicates we have proceed all data that has to be send to remote party,
                // and now are expecting result to be read from it.
                ioBuff.output.map { send =>
                  HandshakeResult(send, closed = false, finished = false)
                }

              case HandshakeStatus.NEED_TASK =>
                // during the handshake we may need again to run tasks
                sslEngineTaskRunner.runDelegatedTasks >> wrap(
                  ioBuff,
                  sslEngine,
                  sslEngineTaskRunner
                )

              case HandshakeStatus.FINISHED =>
                // indicates handshake is completed, and we shall move on and resume normal operation
                // but before send any data that may still be sent to remote party
                ioBuff.output.map { send =>
                  HandshakeResult(send, closed = false, finished = true)
                }
            }

          case Status.BUFFER_OVERFLOW =>
            ioBuff.expandOutput.flatMap { _ =>
              wrap(ioBuff, sslEngine, sslEngineTaskRunner)
            }

          case Status.BUFFER_UNDERFLOW =>
            // impossible during handshake at wrap state
            Sync[F].raiseError(
              new RuntimeException(
                "bug: UNDERFLOW in HANDSHAKE: WRAP. Wrap is always supplied with empty data"
              )
            )

          case Status.CLOSED =>
            ioBuff.output.map { send =>
              HandshakeResult(send, closed = true, finished = false)
            }
        }
      }

  /**
    * Result of unwrap operation.
    * @param out            Data to be sent to application
    * @param closed         The ssl engine is closed
    * @param needWrap       The next handshake operation needs `wrap`. This effectivelly shall
    *                       acquire lock on `wrap` side and perform `wrapHandshake` / `unwrap`
    *                       until handshake is finalized.
    * @param finished       Signals finalization of the handshake.
    * @param handshaking    The ssl is currently handshaking
    */
  case class Result(
      out: Chunk[Byte],
      closed: Boolean,
      needWrap: Boolean,
      finished: Boolean,
      handshaking: Boolean
  )

  /**
    * During handshaking process, signals result of interim handshake operation
    * @param send       Bytes to send to remote party
    * @param closed     Indicates ssl engine was closed
    * @param finished   Indicates ssl engine was closed.
    */
  case class HandshakeResult(
      send: Chunk[Byte],
      closed: Boolean,
      finished: Boolean
  )
}
