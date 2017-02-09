package fs2.io.ssl.tcp


import java.net.SocketAddress
import java.nio.channels.InterruptedByTimeoutException

import fs2.async.mutable.Semaphore
import fs2._
import fs2.io.ssl.SSLEngine
import fs2.io.tcp.Socket
import fs2.util.Async
import fs2.util.Traverse
import fs2.util.syntax._

import scala.concurrent.duration._

/**
  * Created by pach on 29/01/17.
  */
object SSLSocket {

  /**
    * Wraps raw tcp socket with supplied SSLEngine to form SSL Socket
    *
    * Note that engine is swtiched to handshake mode once this is evaluated.
    *
    * @param socket               Raw TCP Socket
    * @param sslEngine            An SSL Engine to use
    */
  def apply[F[_]](
    socket: Socket[F]
    , sslEngine: SSLEngine[F]
  )(
    implicit
    F: Async[F]
  ): F[Socket[F]] = {
    F.refOf(impl.SocketStatus.initial[F]).flatMap { statusRef =>
      async.semaphore(1).flatMap { readSemaphore =>
        async.semaphore(1).flatMap { writeSemaphore =>
          sslEngine.startHandshake.map { _ =>

            new Socket[F] { self =>
              def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = F.suspend {
                val deadline = timeout.map(_.fromNow)
                def go(buff:Vector[Chunk[Byte]]):F[Option[Chunk[Byte]]] = {
                  if (deadline.exists(_.isOverdue())) F.fail(new InterruptedByTimeoutException())
                  else {
                    val sofar = buff.map(_.size).sum
                    if (sofar < numBytes) {
                      self.read(numBytes - sofar, deadline.map(_.timeLeft)).flatMap {
                        case None => if (sofar > 0) F.pure(Some(Chunk.concat(buff))) else F.pure(None)
                        case Some(bytes) => go(buff :+ bytes)
                      }
                    } else F.pure(Some(Chunk.concat(buff)))
                  }
                }
                go(Vector.empty)
              }

              def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
                impl.read(socket, sslEngine, maxBytes, timeout, statusRef, writeSemaphore, readSemaphore)

              def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
                impl.write(socket, sslEngine, bytes, timeout, statusRef, writeSemaphore, readSemaphore)

              def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] = {
                Stream.eval(read(maxBytes,timeout)) flatMap {
                  case Some(bytes) => Stream.chunk(bytes) ++ reads(maxBytes, timeout)
                  case None => Stream.empty
                }
              }

              def writes(timeout: Option[FiniteDuration]): Sink[F, Byte] =
                _.chunks.flatMap { bs => Stream.eval(write(bs, timeout)) }

              def endOfOutput: F[Unit] =
                sslEngine.closeOutbound *> socket.endOfOutput

              def endOfInput: F[Unit] =
                sslEngine.closeInbound *> socket.endOfInput

              def localAddress: F[SocketAddress] = socket.localAddress

              def remoteAddress: F[SocketAddress] = socket.remoteAddress

              def close: F[Unit] = {
                sslEngine.closeInbound *>  sslEngine.closeOutbound *> socket.close
              }
            }


          }}}}
  }


  object impl {

    private val EmptyBytes: Chunk[Byte] = Chunk.bytes(Array.emptyByteArray, 0, 0)

    /**
      * Status of the socket
      * @param buff                   Remaining data that was acquired during handshaking but were not consumed by any
      *                               `read` operation
      * @param handshakeInProgress    indication that handshake is in progress.
      * @param notifyHandshakeDone    If `handshakeInProgress` is true, this contains all operations to be notified
      *                               when handshake is done.
      * @tparam F
      */
    case class SocketStatus[F[_]](
      buff: Chunk[Byte]
      , handshakeInProgress: Boolean
      , notifyHandshakeDone: Vector[F[Unit]]
    )

    object SocketStatus {
      def initial[F[_]] = SocketStatus[F](EmptyBytes, handshakeInProgress = false, Vector.empty)
    }


    /**
      * Write supplied data to the destination socket.
      *
      * When the handshake is in process, this will complete _AFTER_ handshake is complete.
      *
      *
      * @param socket       Socket to write encrypted data to
      * @param sslEngine    SSLEngine to use
      * @param bytes        bytes to write
      * @param timeout      A timeout to write the data
      */
    def write[F[_]](
      socket: Socket[F]
      , sslEngine: SSLEngine[F]
      , bytes: Chunk[Byte]
      , timeout: Option[FiniteDuration]
      , statusRef: Async.Ref[F, SocketStatus[F]]
      , writeSemaphore: Semaphore[F]
      , readSemaphore: Semaphore[F]
    )(implicit F: Async[F]): F[Unit] = {

      def go(bytes: Chunk[Byte]): F[Unit] = {
        writeSemaphore.increment *> {
          sslEngine.wrapAvailable.flatMap { wrapAvailable =>
            sslEngine.wrap(bytes.take(wrapAvailable)).flatMap { result =>
              (if (result.output.nonEmpty) socket.write(result.output, timeout) else F.pure(())) *>
                writeSemaphore.decrement *> {
                if (result.closed) F.pure(())
                else {
                  if (result.handshake.isEmpty) {
                    if (wrapAvailable >= bytes.size) F.pure(())
                    else go(bytes.drop(wrapAvailable))
                  }
                  else {
                    // after succesfull handshake, lets again perform wrap, to make sure all our bytes are written through
                    performHandshake(socket, sslEngine, statusRef, timeout, writeSemaphore, readSemaphore) *> go(bytes.drop(wrapAvailable))
                  }
                }
              }}
          }}}

      go(bytes)
    }

    /**
      * Perform read operation of up to `maxSize` bytes.
      *
      * May return None indicating EOF.
      * If handshake needs to be done during the read process, this will perform that handshake before returning.
      *
      * @param socket               Socket to use to receive data
      * @param sslEngine            SSL Engine
      * @param maxSize              Max size of data to return (may be less or empty)
      * @param timeout              Timeout to await the read operation
      * @param statusRef            A reference to state
      * @param writeSemaphore       Write semaphore guarding any writed
      * @param readSemaphore        Read semaphore guarding any reads
      * @param F
      * @tparam F
      * @return
      */
    def read[F[_]](
      socket: Socket[F]
      , sslEngine: SSLEngine[F]
      , maxSize: Int
      , timeout: Option[FiniteDuration]
      , statusRef: Async.Ref[F, SocketStatus[F]]
      , writeSemaphore: Semaphore[F]
      , readSemaphore: Semaphore[F]
    )(implicit F: Async[F]): F[Option[Chunk[Byte]]] = {
      def go: F[Option[Chunk[Byte]]] = {
        readSemaphore.increment *>
          acquireFromBuffer(maxSize, statusRef).flatMap { fromBuffer =>
            if (fromBuffer.nonEmpty) readSemaphore.decrement.as(Some(fromBuffer))
            else {
              sslEngine.unwrapAvailable.flatMap { unwrapAvailable =>
                socket.read(maxSize min unwrapAvailable).flatMap {
                  case None => readSemaphore.decrement.as(None)
                  case Some(bytes) =>
                    sslEngine.unwrap(bytes).flatMap { result =>
                      // we shall check if handshake is in progress. If yes, we shall put the data read in buffer and
                      // register for handshake to be completed
                      statusRef.get.map(_.handshakeInProgress).flatMap { isHandshakeInProgress =>
                        if (isHandshakeInProgress) {
                          appendToBuffer(result.output, statusRef) *>
                            readSemaphore.decrement *>
                            awaitHandshakeComplete(statusRef) *>
                            go

                        } else {
                          if (result.handshake.isEmpty)  readSemaphore.decrement.as(Some(result.output))
                          else {
                            performHandshake(socket, sslEngine, statusRef, timeout, writeSemaphore, readSemaphore) *> go
                          }
                        }
                      }
                    }
                }}
            }
          }
      }

      go
    }


    /**
      * Invoked when handshake shall be in process and shall finish when handshake is complete
      */
    def awaitHandshakeComplete[F[_]](
      statusRef: Async.Ref[F, SocketStatus[F]]
    )(implicit F: Async[F]) : F[Unit] = {
      F.flatMap(F.ref[Unit]) { signal =>
        F.flatMap(statusRef.modify({ s =>
          if (s.handshakeInProgress) s.copy(notifyHandshakeDone = s.notifyHandshakeDone :+ signal.setPure(()))
          else s
        })) { change =>
          if (change.previous.handshakeInProgress) signal.get
          else F.pure(())
        }}
    }


    /**
      * Perform SSL handshake. This completes when handshake is complete and will trigger all handshake awaiting tasks to
      * be run.
      *
      * @param socket               Socket to send/receive raw data
      * @param sslEngine            SSL engine
      * @param statusRef            Status reference to acquire handshake lock and register for callback
      * @param handshakeTimeout     Timeout to perform the handshake
      * @param F
      * @tparam F
      * @return
      */
    def performHandshake[F[_]](
      socket: Socket[F]
      , sslEngine: SSLEngine[F]
      , statusRef: Async.Ref[F, SocketStatus[F]]
      , handshakeTimeout: Option[FiniteDuration]
      , writeSemaphore: Semaphore[F]
      , readSemaphore: Semaphore[F]
    )(implicit F: Async[F]) : F[Unit] = {
      // todo: correct timeout durations
      // acquires handshake lock or registers when handshake is done
      def acquire: F[Unit] = {
        statusRef.modify { _.copy(handshakeInProgress = true) }.flatMap { change =>
          if (change.previous.handshakeInProgress) {
            awaitHandshakeComplete(statusRef)
          } else {
            // we have acquired handshake lock. lets acquire write and read lock too
            readSemaphore.increment *> writeSemaphore.increment
          }
        }
      }

      // releases previously acquired lock
      // this also evaluates all registered notifications so handshake is complete
      def release: F[Unit] = {
        statusRef.modify(_.copy(handshakeInProgress = false, notifyHandshakeDone = Vector.empty)).flatMap { change =>
          if (change.previous.notifyHandshakeDone.isEmpty) F.pure(())
          else Traverse.vectorInstance.traverse(change.previous.notifyHandshakeDone)(identity).as(())
        } *> readSemaphore.decrement *> writeSemaphore.decrement
      }

      // performs wrap and sends output bytes over wire if any produced.
      // finishes when no wrap/unwrap is needed
      // if wrap is needed again (unlikely) this recurse
      // if unwrap is needed then `unwrap` is invoked
      // note that this is guarded by write semaphore for the wrap and write operation to assure we don't interfere with other write
      def wrap: F[Unit] = {
        def send(data: Vector[Chunk[Byte]]): F[Unit] = {
          val bytes = Chunk.concat(data)
          if (bytes.nonEmpty) socket.write(bytes, handshakeTimeout)
          else F.pure(())
        }

        def go(acc: Vector[Chunk[Byte]]): F[Unit] = {
          sslEngine.wrap(EmptyBytes).flatMap { result =>
            if (result.closed) send(acc :+ result.output) *> release // we are done with SSL
            else {
              result.handshake match {
                case None => send(acc :+ result.output)
                case Some(handshake) => handshake match {
                  case SSLEngine.MoreData.WRAP => go(acc :+ result.output)
                  case SSLEngine.MoreData.UNWRAP => send(acc :+ result.output) *> unwrap(EmptyBytes)
                  case SSLEngine.MoreData.RECEIVE_UNWRAP => send(acc :+ result.output) *> receiveUnwrap
                }
              }
            }
          }
        }
        go(Vector.empty)
      }

      // tries to receive more encrypted data and then performs unwrap
      def receiveUnwrap: F[Unit] = {
        sslEngine.unwrapAvailable.flatMap { available =>
          socket.read(available, handshakeTimeout).flatMap {
            case None => F.pure(())
            case Some(bytes) => unwrap(bytes)
          }}
      }

      // performs unwrap
      // this reads up to 32k of data from the the socket (shall be enough for any SSL handshake message
      // and then performs unwrap.
      // if unwrap is needed again (unlikely) this recurse
      // if wrap is needed then `wrap` is invoked
      def unwrap(bytes: Chunk[Byte]): F[Unit] = {
        sslEngine.unwrap(bytes).flatMap { result =>
          appendToBuffer(result.output, statusRef) *> {
            if (result.closed) F.pure(()) // DONE with SSL
            else {
              result.handshake match {
                case None => F.pure(())
                case Some(handshake) => handshake match {
                  case SSLEngine.MoreData.WRAP => wrap
                  case SSLEngine.MoreData.UNWRAP => unwrap(EmptyBytes)
                  case SSLEngine.MoreData.RECEIVE_UNWRAP => receiveUnwrap
                }
              }
            }
          }}
      }


      // we always perform the wrap again to check if there is still need for unwrap
      // In case this handshake was caused by need of unwrap, this still has to yield to need unwrap, and we
      // assure there that was no concurrent unwrap that had supplied enough data so the handshake is no longer necessary.
      // In case this handshake was caused by need of wrap, then this may produce data that have to be sent to remote party
      acquire *> wrap *> release


    }



    /** concats a, b if they are nonempty **/
    def concat(a: Chunk[Byte], b: Chunk[Byte]) : Chunk[Byte] = {
      if (a.isEmpty) b
      else if (b.isEmpty) a
      else Chunk.concat(Seq(a,b))
    }


    // Causes to append supplied chunk to state buffer, if nonempty
    def appendToBuffer[F[_]](
      chunk:Chunk[Byte]
      , statusRef: Async.Ref[F, SocketStatus[F]]
    )(implicit F: Async[F]):F[Unit] = {
      if (chunk.isEmpty) F.pure(())
      else F.map(statusRef.modify { s => s.copy(buff = concat(s.buff, chunk)) }) { _ => () }
    }


    /**
      * Acquires up to max bytes from the chunk in buffer.
      * May return empty chunk
      */
    def acquireFromBuffer[F[_]](
      max: Int
      , statusRef: Async.Ref[F, SocketStatus[F]]
    )(implicit F: Async[F]):F[Chunk[Byte]] = {
      statusRef.modify2 { s =>
        if (s.buff.isEmpty) s -> EmptyBytes
        else {
          val res = s.buff.take(max)
          val rem = s.buff.drop(max)
          s.copy(buff = rem) -> res
        }
      }.map(_._2)
    }



  }

}