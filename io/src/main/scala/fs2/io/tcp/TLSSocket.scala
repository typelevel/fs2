package fs2
package io
package tcp

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration._

import java.net.SocketAddress
import javax.net.ssl.{SSLEngine, SSLEngineResult}

import cats.Applicative
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.syntax.all._

import fs2.io.tls._
import fs2.io.tls.TLSEngine.{DecryptResult, EncryptResult}

trait TLSSocket[F[_]] extends Socket[F] {

  /** Initiates a new TLS handshake. */
  def startHandshake: F[Unit]
}

object TLSSocket {

  /**
    * Wraps raw tcp socket with supplied SSLEngine to form SSL Socket
    *
    * Note that engine will switch to handshake mode once resulting `F` is evaluated.
    *
    * The resulting socket will not support concurrent reads or concurrent writes
    * (concurrently reading/writing is ok).
    *
    *
    * @param socket               Raw TCP Socket
    * @param tlsEngine            An TSLEngine to use
    */
  def apply[F[_]: Concurrent: ContextShift](
      socket: Socket[F],
      tlsEngine: TLSEngine[F]
  ): F[TLSSocket[F]] =
    for {
      readBuffRef <- Ref.of[F, Queue[Chunk[Byte]]](Queue.empty)
      readSem <- Semaphore(1)
    } yield new TLSSocket[F] { self =>

      /** gets that much data from the buffer if available **/
      private def getFromBuff(max: Int): F[Chunk[Byte]] =
        readBuffRef.modify { takeFromBuff(_, max) }

      // During handshake this start the reader action so we may try
      // to read data from the socket, if required.
      // Started only on `write` thread, during handshake
      // this resolves situation, when user wants just to write data to socket
      // before actually reading them
      def readHandshake(timeout: Option[FiniteDuration]): F[Unit] =
        readSem.withPermit {
          read0(10240, timeout).flatMap {
            case Some(data) if data.nonEmpty => readBuffRef.update { _ :+ data }
            case _                           => Applicative[F].unit
          }
        }

      // like `read` but not guarded by `read` semaphore
      def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        getFromBuff(maxBytes).flatMap { fromBuff =>
          if (fromBuff.nonEmpty) Applicative[F].pure(Some(fromBuff): Option[Chunk[Byte]])
          else {
            def readLoop: F[Option[Chunk[Byte]]] =
              socket.read(maxBytes, timeout).flatMap {
                case Some(data) =>
                  def go(result: DecryptResult[F]): F[Option[Chunk[Byte]]] =
                    result match {
                      case DecryptResult.Decrypted(data) =>
                        if (data.size <= maxBytes) Applicative[F].pure(Some(data))
                        else
                          readBuffRef
                            .update { _ :+ data.drop(maxBytes) }
                            .as(Some(data.take(maxBytes)))

                      case DecryptResult.Handshake(toSend, next) =>
                        if (toSend.isEmpty && next.isEmpty) {
                          // handshake was not able to produce output data
                          // as such another read is required
                          readLoop
                        } else {
                          socket.write(toSend, timeout) >> (next match {
                            case None       => readLoop
                            case Some(next) => next.flatMap(go)
                          })
                        }
                      case DecryptResult.Closed(out) =>
                        Applicative[F].pure(Some(out))
                    }

                  tlsEngine.decrypt(data).flatMap(go)

                case None => Applicative[F].pure(None)
              }

            readLoop
          }
        }
      def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSem.withPermit {
          def go(acc: Chunk.Queue[Byte]): F[Option[Chunk[Byte]]] = {
            val toRead = numBytes - acc.size
            if (toRead <= 0) Applicative[F].pure(Some(acc.toChunk))
            else {
              read0(numBytes, timeout).flatMap {
                case Some(chunk) => go(acc :+ chunk): F[Option[Chunk[Byte]]]
                case None        => Applicative[F].pure(Some(acc.toChunk)): F[Option[Chunk[Byte]]]
              }
            }
          }
          go(Chunk.Queue.empty)
        }

      def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        readSem.withPermit(read0(maxBytes, timeout))

      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] = {
        def go(result: EncryptResult[F]): F[Unit] =
          result match {
            case EncryptResult.Encrypted(data) =>
              Sync[F].delay(println("TLSSocket.write Encrypted")) >> socket.write(data, timeout)

            case EncryptResult.Handshake(data, next) =>
              Sync[F].delay(println("TLSSocket.write Handshake")) >>
                socket.write(data, timeout) >> readHandshake(timeout) >> next.flatMap(go)

            case EncryptResult.Closed() =>
              Sync[F].raiseError(new RuntimeException("TLS Engine is closed"))
          }

        tlsEngine.encrypt(bytes).flatMap(go)
      }

      def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
        Stream.repeatEval(read(maxBytes, timeout)).unNoneTerminate.flatMap(Stream.chunk)

      def writes(timeout: Option[FiniteDuration]): Pipe[F, Byte, Unit] =
        _.chunks.evalMap(write(_, timeout))

      def endOfOutput: F[Unit] =
        tlsEngine.stopEncrypt >> socket.endOfOutput

      def endOfInput: F[Unit] =
        tlsEngine.stopDecrypt >> socket.endOfInput

      def localAddress: F[SocketAddress] =
        socket.localAddress

      def remoteAddress: F[SocketAddress] =
        socket.remoteAddress

      def startHandshake: F[Unit] =
        tlsEngine.startHandshake

      def close: F[Unit] =
        tlsEngine.stopEncrypt >> tlsEngine.stopDecrypt >> socket.close

      def isOpen: F[Boolean] = socket.isOpen
    }

  private def takeFromBuff(
      buff: Queue[Chunk[Byte]],
      max: Int
  ): (Queue[Chunk[Byte]], Chunk[Byte]) = {
    @tailrec
    def go(
        rem: Queue[Chunk[Byte]],
        acc: Chunk.Queue[Byte],
        toGo: Int
    ): (Queue[Chunk[Byte]], Chunk[Byte]) =
      if (toGo <= 0) (rem, acc.toChunk)
      else {
        rem.dequeueOption match {
          case Some((head, tail)) =>
            val add = head.take(toGo)
            val leave = head.drop(toGo)
            if (leave.isEmpty) go(tail, acc :+ add, toGo - add.size)
            else go(leave +: tail, acc :+ add, toGo - add.size)

          case None =>
            go(rem, acc, 0)
        }
      }

    if (buff.isEmpty) (Queue.empty, Chunk.empty)
    else go(buff, Chunk.Queue.empty, max)
  }

  def two[F[_]: Concurrent: ContextShift](
      socket: Socket[F],
      engine: SSLEngine,
      blocker: Blocker
  ): F[TLSSocket[F]] =
    for {
      readSem <- Semaphore(1)
      wrapBuffer <- InputOutputBuffer[F](
        engine.getSession.getApplicationBufferSize,
        engine.getSession.getPacketBufferSize
      )
      unwrapBuffer <- InputOutputBuffer[F](
        engine.getSession.getPacketBufferSize,
        engine.getSession.getApplicationBufferSize
      )
      sslEngineTaskRunner = SSLEngineTaskRunner[F](engine, blocker)
    } yield new TLSSocket[F] {
      private def log(msg: String): F[Unit] =
        Sync[F].delay(println(s"\u001b[33m${msg}\u001b[0m"))

      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        log(s"write($bytes)") >> wrapBuffer.input(bytes) >> doWrap(timeout)

      def doWrap(timeout: Option[FiniteDuration]): F[Unit] =
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
                      socket.write(c, timeout)
                    }
                  case other =>
                    log("sending output chunk") >> wrapBuffer.output.flatMap { c =>
                      socket.write(c, timeout)
                    } >>
                      doHandshake(other, true) >> wrapBuffer.input(Chunk.empty) >> doWrap(timeout)
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                ???
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doWrap(timeout)
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def doUnwrap: F[Option[Chunk[Byte]]] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap { result =>
            log(s"doUnwrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                result.getHandshakeStatus match {
                  case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                    unwrapBuffer.output.map(Some(_))
                  case SSLEngineResult.HandshakeStatus.FINISHED =>
                    unwrapBuffer.output.flatMap { out =>
                      unwrapBuffer.input(Chunk.empty) >> doUnwrap.map(out2 =>
                        Some(Chunk.concat(List(out, out2.getOrElse(Chunk.empty))))
                      )
                    }
                  case other =>
                    doHandshake(other, false) >> unwrapBuffer.input(Chunk.empty) >> doUnwrap
                }
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                unwrapBuffer.output.map(Some(_))
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> doUnwrap
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def doHandshake(
          handshakeStatus: SSLEngineResult.HandshakeStatus,
          lastOperationWrap: Boolean
      ): F[Unit] =
        handshakeStatus match {
          case SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING => ???
          case SSLEngineResult.HandshakeStatus.FINISHED =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              if (remaining > 0) unwrapBuffer.input(Chunk.empty) >> doHsUnwrap
              else Applicative[F].unit
            }
          case SSLEngineResult.HandshakeStatus.NEED_TASK =>
            sslEngineTaskRunner.runDelegatedTasks >> (if (lastOperationWrap) doHsWrap
                                                      else doHsUnwrap)
          case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
            doHsWrap
          case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
            unwrapBuffer.inputRemains.flatMap { remaining =>
              log(s"remaining: $remaining") >> {
                if (remaining > 0) unwrapBuffer.input(Chunk.empty, true) >> doHsUnwrap
                else
                  socket.read(10240).flatMap {
                    case Some(c) => unwrapBuffer.input(c) >> doHsUnwrap
                    case None    => ???
                  }
              }
            }
        }

      def doHsWrap: F[Unit] =
        wrapBuffer.input(Chunk.empty) >> wrapBuffer
          .perform(engine.wrap(_, _))
          .flatTap { result =>
            log(s"doHsWrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                wrapBuffer.output.flatMap(socket.write(_)) >> doHandshake(
                  result.getHandshakeStatus,
                  true
                )
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                ???
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                wrapBuffer.expandOutput >> doHsWrap
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      def doHsUnwrap: F[Unit] =
        unwrapBuffer
          .perform(engine.unwrap(_, _))
          .flatTap { result =>
            log(s"doHsUnwrap result: $result")
          }
          .flatMap { result =>
            result.getStatus match {
              case SSLEngineResult.Status.OK =>
                doHandshake(result.getHandshakeStatus, false)
              case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                if (result.getHandshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                  unwrapBuffer.output >>
                    Applicative[F].unit
                } else if (result.getHandshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                  socket.read(10240).flatMap {
                    case Some(c) =>
                      // TODO don't discard output here
                      unwrapBuffer.output >> unwrapBuffer.input(c) >> doHsUnwrap
                    case None => ???
                  }
                } else if (result.getHandshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                  unwrapBuffer.output >> doHsWrap
                } else {
                  sys.error("What to do here?")
                }
              case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                unwrapBuffer.expandOutput >> unwrapBuffer.input(Chunk.empty) >> doHsUnwrap
              case SSLEngineResult.Status.CLOSED =>
                ???
            }
          }

      private def read0(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        socket.read(maxBytes, timeout).flatMap {
          case Some(c) =>
            unwrapBuffer.input(c) >> doUnwrap
          case None => ???
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
        Sync[F].delay(engine.closeOutbound) >> socket.endOfOutput

      def endOfInput: F[Unit] =
        Sync[F].delay(engine.closeInbound) >> socket.endOfInput

      def localAddress: F[SocketAddress] =
        socket.localAddress

      def remoteAddress: F[SocketAddress] =
        socket.remoteAddress

      def startHandshake: F[Unit] =
        Sync[F].delay(engine.beginHandshake)

      def close: F[Unit] =
        Sync[F].delay(engine.closeOutbound) >> Sync[F].delay(engine.closeInbound) >> socket.close

      def isOpen: F[Boolean] = socket.isOpen
    }
}
