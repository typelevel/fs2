package fs2
package io
package tls

import javax.net.ssl.{SSLEngine, SSLEngineResult}

import cats.{Applicative, Monad}
import cats.effect.{Blocker, Concurrent, ContextShift, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.syntax.all._

import TLSEngine.{DecryptResult, EncryptResult}
import cats.effect.concurrent.Deferred
import fs2.io.tls.TLSEngine.EncryptResult.Handshake

trait TLSEngine[F[_]] {
  /**
    * Starts the SSL Handshake.
    */
  def startHandshake: F[Unit]

  /**
    * Signals that there will be no more data to be encrypted.
    */
  def stopEncrypt: F[Unit]

  /**
    * Signals that there will be no more data received from the network.
    */
  def stopDecrypt: F[Unit]

  /**
    * Used to encrypt data send from the application. This will also send data to the remote porty when completed.
    *
    * Yields to false, in case this Engine was closed for any `encrypt` operation.
    * Otherwise this yields to true
    *
    * @param data   Data to encrypt
    */
  def encrypt(data: Chunk[Byte]): F[EncryptResult[F]]

  /**
    * Consumes received data from the network. User is required to consult this whenever new data
    * from the network are available.
    *
    * Yields to false, if this engine is closed and no more data will be produced.
    *
    * @param data data received from the network.
    */
  def decrypt(data: Chunk[Byte]): F[DecryptResult[F]]
}

object TLSEngine {
  sealed trait EncryptResult[F[_]]

  object EncryptResult {
    /** SSL Engine is closed **/
    case class Closed[F[_]]() extends EncryptResult[F]

    /** Result of the encryption **/
    case class Encrypted[F[_]](data: Chunk[Byte]) extends EncryptResult[F]

    /**
      * During handshake requires hsData to be sent,
      * then, `next` will provide encrypted data
      * @param data     data to be sent as a result of the handshake
      * @param next     Evaluates to next step to take during handshake process.
      *                 When this evaluates to Encrypted() again, the handshake is complete.
      */
    case class Handshake[F[_]](data: Chunk[Byte], next: F[EncryptResult[F]])
        extends EncryptResult[F]
  }

  sealed trait DecryptResult[F[_]]

  object DecryptResult {
    /**
      * SSL engine is closed.
      *
      * The close frame can come together with some remaining user data.
      *
      * @param data The remaining data from the socket.
      */
    case class Closed[F[_]](
        data: Chunk[Byte]
    ) extends DecryptResult[F]

    /** gives decrypted data from the network **/
    case class Decrypted[F[_]](data: Chunk[Byte]) extends DecryptResult[F]

    /**
      * During handshake contains data to be sent to other party.
      * Decrypted data will be available after handshake completes with
      * more successful reads to occur from the remote party.
      *
      * @param data          Data to be sent back to network, during handshake. If empty, user must perform another
      *                      read, before handshake's next step is to be completed and data shall be send to
      *                      remote side.
      * @param signalSent    When nonempty, shall be consulted to obtain next step in the handshake process
      */
    case class Handshake[F[_]](data: Chunk[Byte], signalSent: Option[F[DecryptResult[F]]])
        extends DecryptResult[F]
  }

  /**
    * Creates an TLS engine
    * @param sslEngine underlying java engine
    * @param blocker used for blocking SSL operations in the underlying SSLEngine
    * @tparam F
    * @return
    */
  def apply[F[_]: Concurrent: ContextShift](
      sslEngine: SSLEngine,
      blocker: Blocker
  ): F[TLSEngine[F]] =
    for {
      wrapSem <- Semaphore[F](1)
      unwrapSem <- Semaphore[F](1)
      handshakeFinishedRef <- Ref.of[F, F[Unit]](Applicative[F].unit)
      wrapBuffer <- InputOutputBuffer[F](
        sslEngine.getSession.getApplicationBufferSize,
        sslEngine.getSession.getPacketBufferSize
      )
      unwrapBuffer <- InputOutputBuffer[F](
        sslEngine.getSession.getPacketBufferSize,
        sslEngine.getSession.getApplicationBufferSize
      )
      sslEngineTaskRunner = SSLEngineTaskRunner[F](sslEngine, blocker)
    } yield new TLSEngine[F] {

      private def log(msg: String): F[Unit] =
        Sync[F].delay(println("********** " + msg))
      
      def startHandshake: F[Unit] =
        Sync[F].delay { sslEngine.beginHandshake() }

      def stopEncrypt =
        Sync[F].delay { sslEngine.closeOutbound() }

      def stopDecrypt =
        Sync[F].delay { sslEngine.closeInbound() }

      def encrypt(data: Chunk[Byte]): F[EncryptResult[F]] =
        wrapBuffer.input(data) >> doWrap
        
      private def doWrap: F[EncryptResult[F]] = 
        wrapBuffer.perform((appBuffer, netBuffer) =>
          sslEngine.wrap(appBuffer, netBuffer)
        ).flatMap { result =>
          result.getStatus match {
            case SSLEngineResult.Status.OK =>
              result.getHandshakeStatus match {
                case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                  wrapBuffer.inputRemains.flatMap { remaining =>
                    if (remaining <= 0) wrapBuffer.output.map(chunk => EncryptResult.Encrypted(chunk))
                    else doWrap
                  }
                case SSLEngineResult.HandshakeStatus.FINISHED =>
                  log("doWrap handshake finished") >>
                  handshakeFinishedRef.get.flatten >>
                  wrapBuffer.output.map { out =>
                    EncryptResult.Handshake(out, encrypt(Chunk.empty))
                  }
                case SSLEngineResult.HandshakeStatus.NEED_TASK =>
                  sslEngineTaskRunner.runDelegatedTasks >> doWrap
                case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
                  log("Wrapping and need to unwrap") >>
                  wrapBuffer.output.flatMap { out =>
                    Deferred[F, Unit].flatMap { gate =>
                      handshakeFinishedRef.set(gate.complete(())).as(
                        EncryptResult.Handshake(out, gate.get >> doWrap)
                      )
                    }
                  }
                case SSLEngineResult.HandshakeStatus.NEED_WRAP => ???
              }

            case SSLEngineResult.Status.BUFFER_OVERFLOW => ???
            case SSLEngineResult.Status.BUFFER_UNDERFLOW => ???
            case SSLEngineResult.Status.CLOSED => ???
          }
        }

      def decrypt(data: Chunk[Byte]): F[DecryptResult[F]] =
        unwrapBuffer.input(data) >> doUnwrap

      private def doUnwrap: F[DecryptResult[F]] =
        log("doing an unwrap") >>
        unwrapBuffer.perform(sslEngine.unwrap(_, _)).flatMap { result =>
          result.getStatus match {
            case SSLEngineResult.Status.OK =>
              result.getHandshakeStatus match {
                case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING => ???
                case SSLEngineResult.HandshakeStatus.FINISHED =>
                  unwrapBuffer.output.map { appData =>
                    DecryptResult.Handshake(appData, Some(decrypt(Chunk.empty)))
                  }
                case SSLEngineResult.HandshakeStatus.NEED_TASK =>
                  sslEngineTaskRunner.runDelegatedTasks >> doUnwrap
                case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
                  log("need to unwrap again from unwrap") >>
                  doUnwrap
                case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
                  log("need to wrap from an unwrap") >>
                  encrypt(Chunk.empty).flatMap {
                    case EncryptResult.Handshake(data, _) =>
                      unwrapBuffer.inputRemains.flatMap { rem =>
                        val next2 = if (rem > 0) Some(doUnwrap) else None
                        log("data " + data).as(DecryptResult.Handshake(data, next2))
                      }
                  }
              }

            case SSLEngineResult.Status.BUFFER_OVERFLOW => ???
            case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
              // This doesn't really belong here but it makes the handshake finish
              encrypt(Chunk.empty).flatMap {
                case Handshake(data, next) => 
                  Applicative[F].pure(DecryptResult.Handshake(data, None))
              }
              // Applicative[F].pure(DecryptResult.Handshake(Chunk.empty, None))
            case SSLEngineResult.Status.CLOSED => ???
          }
        }

      override def toString = s"TLSEngine[$sslEngine]"
    }
}
