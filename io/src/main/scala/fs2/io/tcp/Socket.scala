package fs2.io.tcp


import java.net.{StandardSocketOptions, InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousCloseException, AsynchronousServerSocketChannel, CompletionHandler, AsynchronousSocketChannel, AsynchronousChannelGroup}
import java.util.concurrent.TimeUnit

import fs2.Chunk.Bytes
import fs2._
import fs2.Stream._

import scala.concurrent.duration._


trait Socket[F[_]] {

  /**
    * Read up to `maxBytes` from the peer.
    *
    * Evaluates to None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
    * before returning even single byte. Otherwise returns Some(bytes) with bytes that were ready to be read.
    *
    * If `timeout` is specified, then resulting `F` will evaluate to failure with [[java.nio.channels.InterruptedByTimeoutException]]
    * if read was not satisfied in given timeout. Read is satisfied, when at least single Byte was received
    * before `timeout` expires
    *
    * This may return None, as well when end of stream has been reached before timeout expired and no data
    * has been received.
    *
    */
  def read(
    maxBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): F[Option[Bytes]]

  /**
    * Reads stream of bytes from this socket with `read` semantics. Terminates when eof is received.
    * On timeout, this fails with [[java.nio.channels.InterruptedByTimeoutException]].
    */
  def reads(
    maxBytes:Int
    , timeout: Option[FiniteDuration] = None
  ):Stream[F,Bytes]



  /**
    * Read exactly `numBytes` from the peer in a single chunk.
    * If `timeout` is provided and no data arrives within the specified duration, then this results in
    * failure with [[java.nio.channels.InterruptedByTimeoutException]]
    *
    * When returned size of bytes is < `numBytes` that indicates end-of-stream has been reached.
    */
  def readN(
    numBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): F[Option[Bytes]]


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
    bytes: Bytes
    , timeout: Option[FiniteDuration] = None
  ): F[Unit]


  /**
    * Writes supplied stream of bytes to this socket via `write` semantics.
    */
  def writes(
    stream: Stream[F,Bytes]
    , timeout: Option[FiniteDuration] = None
  ): Stream[F,Unit]

}


protected[tcp] object Socket {

  /** see [[fs2.io.tcp.client]] **/
  def client[F[_]](
    to: InetSocketAddress
    , reuseAddress: Boolean
    , sendBufferSize: Int
    , receiveBufferSize: Int
    , keepAlive: Boolean
    , noDelay: Boolean
  )(
    implicit
    AG: AsynchronousChannelGroup
    , F:Async[F]
  ): Stream[F,Socket[F]] = Stream.suspend {

    def setup: Stream[F,AsynchronousSocketChannel] = Stream.suspend {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      Stream.emit(ch)
    }

    def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] = F.async { cb =>
      F.suspend {
        ch.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit = cb(Right(ch))
          def failed(rsn: Throwable, attachment: Void): Unit =  cb(Left(rsn))
        })
      }
    }

    def cleanup(ch: AsynchronousSocketChannel): F[Unit] =
      F.suspend  { ch.close() }


    setup flatMap { ch =>
      Stream.bracket(connect(ch))({_ =>  emit(mkSocket(ch))}, cleanup)
    }

  }



  def server[F[_]](
    bind: InetSocketAddress
    , maxQueued: Int
    , reuseAddress: Boolean
    , receiveBufferSize: Int )(
    implicit AG: AsynchronousChannelGroup
    , F:Async[F]
  ): Stream[F, Stream[F, Socket[F]]] = Stream.suspend {

      def setup: F[AsynchronousServerSocketChannel] = F.suspend {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.bind(bind)
        ch
      }

      def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] = F.suspend{
        if (sch.isOpen) sch.close()
      }

      def acceptIncoming(sch: AsynchronousServerSocketChannel): Stream[F,Stream[F, Socket[F]]] = {
        def go: Stream[F,Stream[F, Socket[F]]] = {
          def acceptChannel: F[AsynchronousSocketChannel] =
            F.async[AsynchronousSocketChannel] { cb => F.pure {
              sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
                def completed(ch: AsynchronousSocketChannel, attachment: Void): Unit = cb(Right(ch))
                def failed(rsn: Throwable, attachment: Void): Unit = cb(Left(rsn))
              })
            }
            }

          def close(ch: AsynchronousSocketChannel): F[Unit] =
            F.bind(F.attempt(F.suspend {
              if (ch.isOpen) ch.close()
            }))(_ => F.pure(()))



          eval(F.attempt(acceptChannel)).map {
            case Left(err) => Stream.empty
            case Right(accepted) => emit(mkSocket(accepted)).onFinalize(close(accepted))
          } ++ go
        }

        go.onError {
          case err: AsynchronousCloseException =>
            if (sch.isOpen) Stream.fail(err)
            else Stream.empty
          case err => Stream.fail(err)
        }
      }


      Stream.bracket(setup)(sch => acceptIncoming(sch), cleanup)



  }


  def mkSocket[F[_]](ch:AsynchronousSocketChannel)(implicit F:Async[F]):Socket[F] = {


    // Reads data to remaining capacity of supplied bytebuffer
    // Also measures time the read took returning this as tuple
    // of (bytes_read, read_duration)
    def readChunk(buff:ByteBuffer, timeoutMs:Long):F[(Int,Long)] = F.async { cb =>  F.pure {
      val started = System.currentTimeMillis()
      ch.read(buff, timeoutMs, TimeUnit.MILLISECONDS, (), new CompletionHandler[Integer, Unit] {
        def completed(result: Integer, attachment: Unit): Unit =  {
          val took = System.currentTimeMillis() - started
          cb(Right((result, took)))
        }
        def failed(err: Throwable, attachment: Unit): Unit = cb(Left(err))
      })
    }}


    def read0(max:Int, timeout:Option[FiniteDuration]):F[Option[Bytes]] = {
      val buff = ByteBuffer.allocate(max)
      F.map(readChunk(buff,timeout.map(_.toMillis).getOrElse(0l))) {
        case (read,_) =>
          if (read < 0) None
          else Some(new Bytes(buff.array(), 0, read))
      }
    }


    def readN0(max:Int, timeout:Option[FiniteDuration]):F[Option[Bytes]] = {
      def go(buff: ByteBuffer, timeoutMs:Long):F[Option[Bytes]] = {
        F.bind(readChunk(buff,timeoutMs)) { case (readBytes, took) =>
          if (readBytes < 0 || buff.remaining() <= 0) F.pure(Some(new Bytes(buff.array(), 0, buff.position())))
          else go(buff,(timeoutMs - took) max 0)
        }
      }
      go(ByteBuffer.allocate(max),timeout.map(_.toMillis).getOrElse(0l))
    }


    def write0(bytes:Bytes,timeout: Option[FiniteDuration]): F[Unit] = {
      def go(buff:ByteBuffer,remains:Long):F[Unit] = {
        F.bind(F.async[Option[Long]] { cb => F.pure {
          val start = System.currentTimeMillis()
          ch.write(buff, remains, TimeUnit.MILLISECONDS, (), new CompletionHandler[Integer, Unit] {
            def completed(result: Integer, attachment: Unit): Unit = {
              if (buff.remaining() <= 0) cb(Right(None))
              else cb(Right(Some(System.currentTimeMillis() - start)))
            }
            def failed(err: Throwable, attachment: Unit): Unit = cb(Left(err))
          })
        }}) {
          case None => F.pure(())
          case Some(took) => go(buff,(remains - took) max 0)
        }
      }


      go(
        ByteBuffer.wrap(bytes.values, bytes.offset, bytes.size)
        , timeout.map(_.toMillis).getOrElse(0l)
      )
    }

    ///////////////////////////////////
    ///////////////////////////////////


    new Socket[F] {
      def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Bytes]] = readN0(numBytes,timeout)
      def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Bytes]] = read0(maxBytes,timeout)
      def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Bytes] = {
        Stream.eval(read(maxBytes,timeout)) flatMap {
          case Some(bytes) => Stream.emit(bytes) ++ reads(maxBytes, timeout)
          case None => Stream.empty
        }
      }

      def write(bytes: Bytes, timeout: Option[FiniteDuration]): F[Unit] = write0(bytes,timeout)
      def writes(stream: Stream[F, Bytes], timeout: Option[FiniteDuration]): Stream[F, Unit] =
        stream.flatMap { bs => Stream.eval(write(bs, timeout)) }

      def localAddress: F[SocketAddress] = F.suspend(ch.getLocalAddress)
      def remoteAddress: F[SocketAddress] = F.suspend(ch.getRemoteAddress)
      def close: F[Unit] = F.suspend(ch.close())
      def endOfOutput: F[Unit] = F.suspend{ ch.shutdownOutput(); () }
      def endOfInput: F[Unit] = F.suspend{ ch.shutdownInput(); () }
    }

  }






}