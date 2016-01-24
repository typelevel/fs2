package fs2.io.tcp

import java.net.{SocketAddress, StandardSocketOptions, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{InterruptedByTimeoutException, AsynchronousServerSocketChannel, CompletionHandler, AsynchronousSocketChannel, AsynchronousChannelGroup}

import fs2.util.Task
import fs2.{async, Strategy, Pull, Async, Stream}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by pach on 24/01/16.
  */
trait TCPSocket[F[_]] {

  /**
    * Read up to `maxBytes` from the peer.
    *
    * Emits once None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
    * before returning even single byte. Otherwise returns Some() with bytes read.
    *
    * If `timeout` is specified, then resulting stream will fail with `InterruptedByTimeoutException` if
    * read was not satisfied in given timeout. Read is satisfied, when at least single Byte was received
    * before `timeout` expires
    *
    * This may return None, as well when end of stream has been reached before timeout expired and any data
    * has been received.
    *
    */
  def available(
    maxBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): Pull[F, Nothing, Option[ByteVector]]

  /**
    * Read exactly `numBytes` from the peer in a single chunk.
    * If `timeout` is provided and no data arrives within the specified duration, the returned
    * `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
    *
    * Emits once, and if emit result's size is < numBytes, then End-Of-Stream state has been reached.
    */
  def readOnce(
    numBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): Pull[F, Nothing, ByteVector]


  /** Indicate that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: F[Unit]

  /** Indicates to peer, we are done writing **/
  def endOfOutput: F[Unit]

  /** Close the connection corresponding to this `Socket`. */
  def close: F[Unit]

  /** Ask for the remote address of the peer. */
  def remoteAddress: F[SocketAddress]

  /** Ask for the local address of the socket. */
  def localAddress: F[SocketAddress]

  /**
    * Write `bytes` to the peer. If `timeout` is provided
    * and the operation does not complete in the specified duration,
    * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
    *
    * Completes when bytes are written to the socket.
    *
    */
  def write(
    bytes: ByteVector
    , timeout: Option[FiniteDuration] = None
  ): F[Unit]

}


object TCPSocket {

  def client[F[_]](
    to: InetSocketAddress
    , reuseAddress: Boolean = true
    , sendBufferSize: Int = 256 * 1024
    , receiveBufferSize: Int = 256 * 1024
    , keepAlive: Boolean = false
    , noDelay: Boolean = false
  )(implicit AG: AsynchronousChannelGroup, F: Async[F])
  : Pull[F, Nothing, TCPSocket[F]] = Pull suspend {

    def setup: F[AsynchronousSocketChannel] = F.suspend {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] = F.async { cb =>
      F.suspend {
        ch.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit = cb(Right(ch))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(Left(rsn))
        })
      }
    }

    def cleanup(ch: AsynchronousSocketChannel): F[Unit] =
      F.suspend(ch.close())


    Pull.acquire(Stream.token, F.bind(setup)(connect), cleanup) map buildSocket(F)
  }


  def server[F[_]:Async](
    bind: InetSocketAddress
    , maxQueued: Int = 0
    , reuseAddress: Boolean = true
    , receiveBufferSize: Int = 256 * 1024)(
    implicit AG: AsynchronousChannelGroup, F: Async[F]
  ): Stream[F, Pull[F, Nothing, TCPSocket[F]]] = {

    def setup: F[AsynchronousServerSocketChannel] = F.suspend {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.bind(bind)
    }

    def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] =
      F.suspend { sch.close() }


    def acceptIncoming(sch: AsynchronousServerSocketChannel): Pull[F, Nothing, TCPSocket[F]] = Pull.suspend {
      def accept: F[AsynchronousSocketChannel] =
        F.async { cb => F.suspend {
          sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
            def completed(result: AsynchronousSocketChannel, attachment: Void): Unit = cb(Right(result))
            def failed(rsn: Throwable, attachment: Void): Unit = cb(Left(rsn))
          })
        }}
      def close(ch: AsynchronousSocketChannel): F[Unit] =
        F.suspend { ch.close() }

      Pull.acquire(Stream.token,accept,close) map buildSocket(F)
    }

    def handleIncoming(sch: AsynchronousServerSocketChannel):Stream[F,Pull[F, Nothing, TCPSocket[F]]] =
      Stream.constant(sch) map acceptIncoming

    Stream.bracket(setup)(handleIncoming, cleanup)
  }


  private def buildSocket[F[_]](F: Async[F])(ch:AsynchronousSocketChannel):TCPSocket[F] = {
    new TCPSocket[F] {
      def remoteAddress: F[SocketAddress] = F.suspend(ch.getRemoteAddress)
      def localAddress: F[SocketAddress] = F.suspend(ch.getLocalAddress)
      def close: F[Unit] = F.suspend(ch.close())
      def endOfOutput: F[Unit] = F.suspend(ch.shutdownOutput())
      def endOfInput: F[Unit] = F.suspend(ch.shutdownInput())

      def available(maxBytes: Int, timeout: Option[FiniteDuration]): Pull[F, Nothing, Option[ByteVector]] =
        Pull.eval {
          F.async[Option[ByteVector]] { cb => F.suspend {
            val arr = new Array[Byte](maxBytes)
            val buf = ByteBuffer.wrap(arr)
            val handler = new CompletionHandler[Integer, Void] {
              def completed(result: Integer, attachment: Void): Unit = {
                try {
                  buf.flip()
                  val bs = ByteVector.view(buf) // no need to copy since `buf` is not reused
                  if (result.intValue < 0) cb(Right(None))
                  else cb(Right(Some(bs.take(result.intValue))))
                }
                catch { case e: Throwable => cb(Left(e)) }
              }
              def failed(exc: Throwable, attachment: Void): Unit = cb(Left(exc))
            }

            timeout.fold(ch.read(buf, null, handler)) { duration =>
              ch.read(buf, duration.length, duration.unit, null, handler)
            }

          }}
        }


      def readOnce(numBytes: Int, timeout: Option[FiniteDuration]): Pull[F, Nothing, ByteVector] = {
        def go(acc: ByteVector, rem: Option[FiniteDuration]): Pull[F, Nothing, ByteVector] = {
          if (rem.exists(_ <= 0.millis)) Pull.fail(new InterruptedByTimeoutException())
          else {
            val start = timeout.fold(0l)(_ => System.currentTimeMillis())
            available(numBytes - acc.size, rem) flatMap {
              case None =>  Pull.pure(acc)
              case Some(read) =>
                if (read.size + acc.size == numBytes) Pull.pure(acc ++ read)
                else {
                  go(
                    acc ++ read
                    , rem.map(_ - (System.currentTimeMillis() - start).millis)
                  )
                }

            }
          }
        }

        go(ByteVector.empty, timeout)
      }


      def write(bytes: ByteVector, timeout: Option[FiniteDuration]): F[Unit] = {
        if (bytes.isEmpty) F.pure(())
        else {
          F.bind(F.async[(Int, Option[FiniteDuration])] { cb => F.suspend {
            val now = timeout.fold(0l)(_ => System.currentTimeMillis())
            val handler = new CompletionHandler[Integer, Void] {
              def completed(result: Integer, attachment: Void): Unit = {
                val remains = timeout.map(dur => dur - (System.currentTimeMillis() - now).millis)
                cb(Right(result.intValue() -> remains))
              }
              def failed(exc: Throwable, attachment: Void): Unit = cb(Left(exc))
            }
            timeout match {
              case None => ch.write(bytes.toByteBuffer, null, handler)
              case Some(d) => ch.write(bytes.toByteBuffer, d.length, d.unit, null, handler)
            }
          }})({ case (w,remains) =>
            if (w == bytes.length) F.pure(())
            else {
              if (remains.exists(_ <= 0.millis)) F.fail(new InterruptedByTimeoutException())
              else write(bytes.drop(w), remains)
            }
          })
        }
      }

    }
  }

}
