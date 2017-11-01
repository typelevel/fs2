package fs2.crypto.io.tcp


import java.net.SocketAddress
import java.nio.channels.InterruptedByTimeoutException

import fs2.async.mutable.Semaphore
import fs2._
import fs2.io.tcp.Socket
import fs2.util.{Async, Traverse}
import fs2.util.syntax._
import fs2.crypto.TLSEngine
import fs2.crypto.TLSEngine.MoreData

import scala.concurrent.duration._

trait TLSSocket[F[_]] extends Socket[F] {

  /** when invoked, will initiate new TLS Handshake **/
  def startHandshake: F[Unit]

}


/**
  * Created by pach on 29/01/17.
  */
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
  def apply[F[_]](
    socket: Socket[F]
    , tlsEngine: TLSEngine[F]
  )(
    implicit F: Async[F]
  ): F[TLSSocket[F]] = {
    async.semaphore(1).flatMap { wrapSemaphore =>
    F.refOf(impl.ReadStatus[F](reading = false, Vector.empty, Chunk.empty)) flatMap { readStateRef =>
    tlsEngine.startHandshake as {

      new TLSSocket[F] { self =>
        def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = F.suspend {
          val deadline = timeout.map(_.fromNow)
          def go(buff:Vector[Chunk[Byte]]):F[Option[Chunk[Byte]]] = {
            if (deadline.exists(_.isOverdue())) F.fail(new InterruptedByTimeoutException())
            else {
              val sofar = buff.map(_.size).sum
              if (sofar < numBytes) {
                self.read(numBytes - sofar, deadline.map(_.timeLeft)).flatMap {
                  case None => if (sofar > 0) F.pure(Some(Chunk.concatBytes(buff))) else F.pure(None)
                  case Some(bytes) => go(buff :+ bytes)
                }
              } else F.pure(Some(Chunk.concatBytes(buff)))
            }
          }
          go(Vector.empty)
        }

        val handshakeW = impl.performWriteHandshake(socket, tlsEngine, readStateRef, wrapSemaphore) _

        val handshakeR = impl.performReadHandshake(socket, tlsEngine, readStateRef, wrapSemaphore) _

        val read_ = impl.read(socket, tlsEngine, handshakeR, readStateRef) _

        val write_  = impl.write(socket, tlsEngine, handshakeW, wrapSemaphore) _

        def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
          read_(maxBytes, timeout)

        def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
          write_(bytes, timeout)

        def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] = {
          Stream.eval(read(maxBytes,timeout)) flatMap {
            case Some(bytes) => Stream.chunk(bytes) ++ reads(maxBytes, timeout)
            case None => Stream.empty
          }
        }

        def writes(timeout: Option[FiniteDuration]): Sink[F, Byte] =
          _.chunks.flatMap { bs => Stream.eval(write(bs, timeout)) }

        def endOfOutput: F[Unit] =
          tlsEngine.closeOutbound *> socket.endOfOutput

        def endOfInput: F[Unit] =
          tlsEngine.closeInbound *> socket.endOfInput

        def localAddress: F[SocketAddress] =
          socket.localAddress

        def remoteAddress: F[SocketAddress] =
          socket.remoteAddress

        def startHandshake: F[Unit] =
          tlsEngine.startHandshake

        def close: F[Unit] =
          tlsEngine.closeInbound *> tlsEngine.closeOutbound *> socket.close

      }


    }}}
  }


  private[tcp] object impl {

    private val EmptyBytes: Chunk[Byte] = Chunk.bytes(Array.emptyByteArray, 0, 0)

    /**
      * Reading status of the TLSSocket
      * @param reading          When true, there is someone alse reading currently
      * @param readDone         Contains signals (reads) to be notified when current read completes.
      * @tparam F
      */
    case class ReadStatus[F[_]](
      reading: Boolean
      , readDone: Vector[F[Unit]]
      , buff: Chunk[Byte]
    )


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
      , sslEngine: TLSEngine[F]
      , handshake: (MoreData.Value) => F[Unit]
      , writeSemaphore: Semaphore[F]
    )(
      bytes: Chunk[Byte]
      , timeout: Option[FiniteDuration]
    )(implicit F: Async[F]): F[Unit] = {
        def go(bytes: Chunk[Byte]): F[Unit] = {
        if (bytes.isEmpty) F.pure(())
        else {
          writeSemaphore.decrement *>
          sslEngine.wrapAvailable.flatMap { wrapAvailable =>
          sslEngine.wrap(bytes.take(wrapAvailable)).flatMap { result =>
            (if (result.output.nonEmpty) { socket.write(result.output, timeout) } else F.pure(())) *>
            writeSemaphore.increment *> { // data sent over wire, safe to decrement
              if (result.closed) F.pure(())
              else {
                result.handshake match {
                  case None =>
                    // the operation is done, drop the chunk which we have wrote so far, and try to write remainder.
                    go(bytes.drop(wrapAvailable))

                  case Some(action) =>
                    // switch to handshake and when complete revert back to writing remainder of data
                    handshake(action) *>
                    go(bytes.drop(wrapAvailable))
                }
              }}
            }
          }
        }
      }

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
      * @param readHandshake        `F` that when evaluated will perform handshake while read lock is acauired.
      */
    def read[F[_]](
      socket: Socket[F]
      , sslEngine: TLSEngine[F]
      , readHandshake:  (MoreData.Value) => F[Unit]
      , readStateRef: Async.Ref[F, ReadStatus[F]]
    )(
      maxSize: Int
      , timeout: Option[FiniteDuration]
    )(implicit F: Async[F]): F[Option[Chunk[Byte]]] = {

      def go: F[Option[Chunk[Byte]]] = {
        acquireReadLock(readStateRef) *>
        acquireFromBuffer(maxSize, readStateRef).flatMap { fromBuffer =>
          if (fromBuffer.nonEmpty) releaseReadLock(readStateRef) as Some(fromBuffer)
          else {
            sslEngine.unwrapAvailable.flatMap { unwrapAvailable =>
            socket.read(maxSize min unwrapAvailable).flatMap {
              case None => releaseReadLock(readStateRef) as None
              case Some(bytes) =>
                sslEngine.unwrap(bytes).flatMap { result =>
                  result.handshake match {
                    // note the buffer in state is empty at this place we don't have to consult it
                    case None =>
                      if (result.output.size <= maxSize)
                        releaseReadLock(readStateRef) as Some(result.output)
                      else {
                        // return only up to maxSize, remainder put to state for next read.
                        val out = result.output.take(maxSize)
                        val remainder = result.output.drop(maxSize)

                        appendToBuffer(remainder, readStateRef) *>
                        releaseReadLock(readStateRef) as Some(out)
                      }

                    case Some(action) =>
                      // the action ask for other handshake operation to proceed
                      // we still may have received partial data that has to be added as result of this operation
                      appendToBuffer(result.output, readStateRef) *>
                      readHandshake(action) *>
                      releaseReadLock(readStateRef) *> // this gives chance to writes to proceed
                      go
                  }
                }
            }}
          }
        }
      }

      go
    }



    /**
      * Perfroms handshake from the read side. This is only called when read lock is acquired.
      *
      * @param socket         Raw tcp socket
      * @param tlsEngine      TLS Engine to use
      * @param readStateRef   Ref to keep read state in
      * @param wrapSemaphore  A semaphore tu guard wrap and sent operation.
      */
    def performReadHandshake[F[_]](
      socket: Socket[F]
      , tlsEngine: TLSEngine[F]
      , readStateRef: Async.Ref[F, ReadStatus[F]]
      , wrapSemaphore: Semaphore[F]

    )(
      action: MoreData.Value
    )(implicit F: Async[F]): F[Unit] = {

      // performs single wrap operation. based on result, this may
      // require further /wrap/unwrap or may terminate.
      def wrap: F[Unit] = {
        wrapSemaphore.decrement *>
        tlsEngine.wrap(EmptyBytes).flatMap { result =>
          (if (result.output.nonEmpty) socket.write(result.output, None) else F.pure(())) *>
            wrapSemaphore.increment *> {
            if (result.closed) F.pure(())
            else {
              result.handshake match {
                case None => F.pure(()) // done with handshake
                case Some(action) => handleAction(action)
              }
            }
          }
        }
      }

      // note the read lock is acquired
      def receiveUnwrap: F[Unit] = {
        tlsEngine.unwrapAvailable.flatMap { available =>
        socket.read(available, None).flatMap {
            case None => F.pure(())
            case Some(bytes) => unwrap(bytes)
        }}
      }

      // note that read lock is acquired
      def unwrap(bytes: Chunk[Byte]): F[Unit] = {
        tlsEngine.unwrap(bytes) flatMap { result =>
          appendToBuffer(result.output, readStateRef) *> {
            if (result.closed) F.pure(()) // done with handshake
            else {
              result.handshake match {
                case None => F.pure(())
                case Some(action) => handleAction(action)
              }
            }
          }
        }
      }

      def handleAction(action: MoreData.Value): F[Unit] = {
        action match {
          case TLSEngine.MoreData.WRAP => wrap
          case TLSEngine.MoreData.UNWRAP => unwrap(EmptyBytes)
          case TLSEngine.MoreData.RECEIVE_UNWRAP => receiveUnwrap
        }
      }

      handleAction(action)

    }


    /**
      * Perform SSL handshake.  Called from `write` operation. This is called without any lock (semaphore, read lock)
      * to be acquired. When necessary this will acquire read lock, or will suspend operation until read lock is released.
      * When the read lock is released, the andshake may be already completed, and this will just terminate.
      *
      * @param socket         Raw tcp socket
      * @param tlsEngine      TLS Engine to use
      * @param readStateRef   Ref to keep read state in
      * @param wrapSemaphore  A semaphore tu guard wrap and sent operation.
      */
    def performWriteHandshake[F[_]](
      socket: Socket[F]
      , tlsEngine: TLSEngine[F]
      , readStateRef: Async.Ref[F, ReadStatus[F]]
      , wrapSemaphore: Semaphore[F]
    )(
      action: MoreData.Value
    )(implicit F: Async[F]) : F[Unit] = {
      def go(action: MoreData.Value): F[Unit] = {
        acquireReadLock(readStateRef) flatMap { acquired =>
          if (acquired) {
            performReadHandshake(socket, tlsEngine, readStateRef, wrapSemaphore)(action) *>
              releaseReadLock(readStateRef)
          } else {
            // perform wrap of empty input. If the wrap results in any action, then retry whole again
            wrapSemaphore.decrement *>
              tlsEngine.wrap(EmptyBytes).flatMap { result =>
                (if (result.output.nonEmpty) socket.write(result.output, None) else F.pure(())) *>
                  wrapSemaphore.increment *> {
                  result.handshake match {
                    case None => F.pure(()) // end of handshake
                    case Some(action) => go(action)
                  }
                }
              }
          }

        }
      }

      go(action)
    }


      /**
        * Acquires read lock. Release by `releaseReadLock`
        *
        * If this returns true that indicates the lock was acquired and must be released when the operation completes
        * If this return false, the lock is not acquired, but blocking read operation has been completed, likely with finishing the handshake.
        * As such, we have to retry wrap again.
        *
        * @return
        */
      def acquireReadLock[F[_]](stateRef: Async.Ref[F, ReadStatus[F]])(implicit F: Async[F]): F[Boolean] = {
        stateRef modify { _.copy(reading = true) } flatMap { c =>
          if (! c.previous.reading) F.pure(true)
          else {
            F.ref[Unit] flatMap { signal =>
            stateRef modify { rs => if (rs.reading) rs.copy(readDone = signal.setPure(()) +: rs.readDone) else rs.copy(reading = true) } flatMap { c =>
              if (!c.previous.reading) F.pure(true)
              else (signal.get as false)
            }}
          }
        }
      }

      /**
        * Releases previously acquired readLock.
        * Note that this will cause all awaiting contemptors to be run.
        * @return
        */
      def releaseReadLock[F[_]](stateRef: Async.Ref[F, ReadStatus[F]])(implicit F: Async[F]): F[Unit] = {
        stateRef modify { _.copy( reading = false, readDone = Vector.empty ) } flatMap { c =>
          Traverse.vectorInstance.sequence(c.previous.readDone) as (())
        }
      }


    /**
      * During normal operation or during handshake, we may received user data at unwrap, that cannot be consumed
      * or sent to client. These data are put to temporary buffer, and will be available at next `read` operation of the client.
      * @param chunk        Chunk to append
      * @param statusRef    Status ref to update the buffer in
      */
    def appendToBuffer[F[_]](
      chunk:Chunk[Byte]
      , statusRef: Async.Ref[F, ReadStatus[F]]
    )(implicit F: Async[F]):F[Unit] = {
      if (chunk.isEmpty) F.pure(())
      else statusRef.modify { s => s.copy(buff = Chunk.concat(Seq(s.buff, chunk))) } as (())
    }


    /**
      * Acquires up to max bytes from the chunk in buffer.
      * May return empty chunk
      */
    def acquireFromBuffer[F[_]](
      max: Int
      , statusRef: Async.Ref[F, ReadStatus[F]]
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
