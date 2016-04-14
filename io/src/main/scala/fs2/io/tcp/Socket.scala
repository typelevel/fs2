package fs2.io.tcp


import java.net.{StandardSocketOptions, InetSocketAddress, SocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousServerSocketChannel, CompletionHandler, AsynchronousSocketChannel, AsynchronousChannelGroup}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
          def completed(result: Void, attachment: Void): Unit = {
            println(("#### CONNECTED", ch))
            cb(Right(ch))
          }
          def failed(rsn: Throwable, attachment: Void): Unit = {
            println(("#### FAIL CX", ch, rsn))
            cb(Left(rsn))
          }
        })
      }
    }

    def cleanup(ch: AsynchronousSocketChannel): F[Unit] = {
      F.suspend  {  println(("XXXR CLOSE CLIENT", ch));  ch.close() }
    }

    setup flatMap { ch =>
      Stream.bracket(connect(ch))({_ => println("XXXY CONNECTED" -> ch); emit(mkSocket(ch))}, cleanup)
    }

  }



  def server[F[_]](
    bind: InetSocketAddress
    , maxQueued: Int
    , reuseAddress: Boolean
    , receiveBufferSize: Int )(
    implicit AG: AsynchronousChannelGroup
    , F:Async[F]
  ): Stream[F, Stream[F, Socket[F]]] =  {
    Stream.eval(async.signalOf((0,false))) flatMap { signal =>
      // we keep in signal number of open channels and whether the server socket is closed or not
      // When outer server channel terminate for whatever reason (or is killed),
      // then we signal to all inner channels that server was terminated and then we monitor the
      // number of channels open, and when that reaches the `0` then and only then
      // we will `close` the server channel.
      // also at the same time, the accepted channels are killed before the `server` channel terminates


      def setup: F[AsynchronousServerSocketChannel] = F.suspend {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.bind(bind)
        ch
      }

      def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] = {
        F.bind(signal.possiblyModify { case (cnt,_) => Some((cnt,true))}) { _ =>
          F.map(signal.discrete.find { case(cnt,_) => ! (cnt > 0) }.run.run) { _ =>
            println("XXXR*** SERVER SOCKET channel closed"); sch.close()
          }
        }
      }

      val acceptsOpen=new AtomicInteger(0)

      def acceptIncoming(sch: AsynchronousServerSocketChannel): Stream[F,Stream[F, Socket[F]]] = {
        eval_(F.suspend(println("XXXY Accepting"))) ++
        eval(signal.get) flatMap  {
          case (count,true) =>
            println(("XXXR CLOSED:", count))
            empty[F,Stream[F, Socket[F]]]
          case (count,false) =>
            def acceptChannel:F[AsynchronousSocketChannel] = F.async { cb => F.pure {
              try {
                sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
                  def completed(ch: AsynchronousSocketChannel, attachment: Void): Unit = {
                    println(("XXXG COMPLETED ACCEPT", count, acceptsOpen.decrementAndGet()))
                    cb(Right(ch))
                  }
                  def failed(rsn: Throwable, attachment: Void): Unit = {
                    println(("XXXG ACCEPT FAILED", sch, rsn))
                    cb(Left(rsn))
                  }
                })
              } catch {
                case t: Throwable => t.printStackTrace(); throw t
              }
            }}

            def close(ch:AsynchronousSocketChannel):F[Unit] = {
              F.bind(F.suspend{println(("XXXR Closing ", ch));ch.close()}) { _ =>
                F.map(signal.possiblyModify { case (cnt,closed ) => Some((cnt -1, closed)) })(_ => ())
              }
            }


            eval(acceptChannel) flatMap { accepted =>
              val increment = signal.possiblyModify { case (cnt,closed) => Some((cnt + 1, closed)) }
               eval_(increment) ++ emit(emit(mkSocket(accepted))) ++ acceptIncoming(sch)
               // emit(bracket(increment)(_ => emit(mkSocket(accepted)),_ => close(accepted))) ++ acceptIncoming(sch)
            }


        }
      }





      Stream.bracket(setup)(sch => acceptIncoming(sch), cleanup)

    }
  }


  def mkSocket[F[_]](ch:AsynchronousSocketChannel)(implicit F:Async[F]):Socket[F] = {


    // Reads data to remaining capacity of supplied bytebuffer
    // Also measures time the read took returning this as tuple
    // of (bytes_read, read_duration)
    def readChunk(buff:ByteBuffer, timeoutMs:Long):F[(Int,Long)] = F.async { cb =>  F.pure {
      println(("XXX READING CHUNK", buff, timeoutMs))
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
      println(("XXX READING0", max))
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
        println(("XXX WRITING CHUNK", buff, remains))
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
        (Stream.eval(read(maxBytes,timeout)) flatMap {
          case Some(bytes) =>
            println(("XXXG READ", bytes))
            Stream.emit(bytes) ++ reads(maxBytes, timeout)
          case None =>
            println(("XXXG DONE"))
            Stream.empty
        }).onComplete(Stream.eval_(F.suspend(println("READS TERMINATED"))))
      }

      def write(bytes: Bytes, timeout: Option[FiniteDuration]): F[Unit] = write0(bytes,timeout)
      def writes(stream: Stream[F, Bytes], timeout: Option[FiniteDuration]): Stream[F, Unit] =
        stream.flatMap { bs => Stream.eval(write(bs, timeout)) ++ Stream.eval_(F.suspend(println("WRITE DONE"))) }
        .onComplete(Stream.eval_(F.suspend(println("WRITES TERMINATED"))))

      def localAddress: F[SocketAddress] = F.suspend(ch.getLocalAddress)
      def remoteAddress: F[SocketAddress] = F.suspend(ch.getRemoteAddress)

      def close: F[Unit] = F.suspend(ch.close())
      def endOfOutput: F[Unit] = F.suspend{ ch.shutdownOutput(); () }
      def endOfInput: F[Unit] = F.suspend{ ch.shutdownInput(); () }
    }

  }






}