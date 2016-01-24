package fs2.io

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler, InterruptedByTimeoutException}

import fs2._
import fs2.util.{Task, ~>}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by pach on 24/12/15.
  */
object tcp {

  import impl.{Socket, socket}

  /**
    * A `Task[A]` which has access to an `Socket`, for reading/writing from
    * some network resource.
    */
  sealed trait Connection[A] {
    private[io] def run(channel: Socket, S: Strategy): Task[A]
  }


  /**
    * Process that connects to remote server (TCP) and runs the stream `ouput`.
    *
    * @param to              Address of remote server
    * @param reuseAddress    whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param sendBufferSize  size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
    * @param receiveBufferSize   size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    * @param keepAlive       whether keep-alive on tcp is used (@see [[java.net.StandardSocketOptions.SO_KEEPALIVE]])
    * @param noDelay         whether tcp no-delay flag is set  (@see [[java.net.StandardSocketOptions.TCP_NODELAY]])
    */
  def connect[A](
    to: InetSocketAddress
    , reuseAddress: Boolean = true
    , sendBufferSize: Int = 256 * 1024
    , receiveBufferSize: Int = 256 * 1024
    , keepAlive: Boolean = false
    , noDelay: Boolean = false
  )(output: Stream[Connection, A])(
    implicit AG: AsynchronousChannelGroup, S: Strategy
  ): Stream[Task, A] = {

    def setup: Task[AsynchronousSocketChannel] = Task.delay {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def connect(ch: AsynchronousSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        ch.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit =
            cb(Right(ch))

          def failed(rsn: Throwable, attachment: Void): Unit =
            cb(Left(rsn))
        })
      }

    def use(ch: AsynchronousSocketChannel): Stream[Task, A] =
      impl.bindTo(impl.asynchronous(ch), S)(output)

    def cleanup(ch: AsynchronousSocketChannel): Task[Unit] =
      Task.delay(ch.close())

    Stream.bracket(setup flatMap connect)(use, cleanup)
  }


  /**
    * Process that binds to supplied address and handles incoming TCP connections
    * using the specified handler.
    *
    * The outer stream returned scopes the lifetime of the server socket.
    * When the returned process terminates, all open connections will terminate as well.
    *
    * The inner streams represents individual connections, handled by `handler`. If
    * any inner stream fails, this will _NOT_ cause the server connection to fail/close/terminate.
    *
    * @param bind               address to which this process has to be bound
    * @param maxQueued          Number of queued requests before they will become rejected by server
    *                           Supply <= 0 if unbounded
    * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param receiveBufferSize  size of receive buffer (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    */
  def server[A](
    bind: InetSocketAddress
    , maxQueued: Int = 0
    , reuseAddress: Boolean = true
    , receiveBufferSize: Int = 256 * 1024)(handler: Stream[Connection, A]
  )(
    implicit AG: AsynchronousChannelGroup, S: Strategy
  ): Stream[Task, Stream[Task, A]] = {

    def setup: Task[AsynchronousServerSocketChannel] = Task.delay {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.bind(bind)
    }

    def accept(sch: AsynchronousServerSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit =
            S {cb(Right(result))}

          def failed(rsn: Throwable, attachment: Void): Unit =
            S {cb(Left(rsn))}
        })
      }

    def processRequest(c: AsynchronousSocketChannel): Stream[Task, A] = {
      impl.bindTo(impl.asynchronous(c), S)(handler)
      .onComplete(Stream.eval_(closeAccepted(c)))
    }

    def cleanup(sch: AsynchronousServerSocketChannel): Task[Unit] = Task.delay {
      sch.close()
    }

    def closeAccepted(ch: AsynchronousSocketChannel): Task[Unit] = Task.delay {
      ch.close()
    }


    def handleIncoming(sch: AsynchronousServerSocketChannel): Stream[Task, Stream[Task, A]] = {
      Stream.eval(
        if (maxQueued <= 0) async.unboundedQueue[Task, AsynchronousSocketChannel]
        else async.boundedQueue[Task, AsynchronousSocketChannel](maxQueued)
      ) flatMap { queue =>
        val enqueueAccepted: Stream[Task, Unit] =
          Stream.repeatEval(accept(sch)).flatMap { accepted =>
            Stream.eval(queue.offer1(accepted).flatMap { queued =>
              if (queued) Task.now(())
              else closeAccepted(accepted)
            })
          }

        val processAll: Stream[Task, Stream[Task, A]] =
          queue.dequeue.map {processRequest}

        enqueueAccepted.drain merge processAll
      }
    }

    Stream.bracket(setup)(handleIncoming, cleanup)

  }

  //////////////////////////////////////////////////////
  // Alphabetic order api
  //////////////////////////////////////////////////////

  /**
    * Read up to `maxBytes` from the peer.
    *
    * Emits once None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
    * before returning even single byte. Otherwise returns Some() with bytes read.
    *
    * If `timeout` is specified, then resulting stream will fail with `InterruptedByTimeoutException` if
    * read was not satisfied in given timeout.
    *
    * This may return None, as well when end of stream has been reached before timeout expired and any data
    * has been received.
    *
    */
  def available(
    maxBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, Option[ByteVector]] =
    socket flatMap {_.available(maxBytes, timeout)}


  /** Defined as `tcp.writes(chunks,timeout,allowPeerClosed) onComplete endOfOutput`. */
  def lastWrites(
    chunks: Stream[Connection, ByteVector]
    , timeout: Option[FiniteDuration] = None
    , allowPeerClosed: Boolean = false
  ): Stream[Connection, Unit] =
    writes(chunks, timeout) onComplete endOfOutput

  /** Like [[scalaz.stream.tcp.lastWrites]], but ignores the output. */
  def lastWrites_(
    chunks: Stream[Connection, ByteVector]
    , timeout: Option[FiniteDuration] = None
    , allowPeerClosed: Boolean = false
  ): Stream[Connection, Nothing] =
    lastWrites(chunks, timeout).drain

  /** Lift a regular `Process` to operate in the context of a `Connection`. */
  def lift[A](p: Stream[Task, A]): Stream[Connection, A] =
    p.translate(new (Task ~> Connection) {def apply[x](t: Task[x]) = liftT(t)})

  /** lifts `Task` to operate in context of Connection **/
  def liftT[A](t: Task[A]): Connection[A] =
    new Connection[A] {def run(channel: Socket, S: Strategy) = t}

  /** Returns a single-element stream containing the local address of the connection. */
  def localAddress: Stream[Connection, InetSocketAddress] =
    socket flatMap { e => eval(e.localAddress) }

  /** Indicate that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: Stream[Connection, Unit] =
    socket flatMap { e => eval(e.endOfInput) }

  /** Indicates to peer, we are done writing **/
  def endOfOutput: Stream[Connection, Unit] =
    socket flatMap { e => eval(e.endOfOutput) }

  /** Evaluate and emit the result of `t`. */
  def eval[A](t: Task[A]): Stream[Connection, A] =
    Stream.eval(liftT(t))

  /** Evaluate and ignore the result of `t`. */
  def eval_[A](t: Task[A]): Stream[Connection, Nothing] =
    Stream.eval_(liftT(t))

  /** merges two Connection Streams together. Allows for example for concurrent read and write **/
  def merge[A](a: Stream[Connection, A], a2: Stream[Connection, A])(implicit S: Strategy): Stream[Connection, A] =
    socket flatMap { socket => lift {impl.bindTo(socket, S)(a) merge impl.bindTo(socket, S)(a2)} }


  /**
    * Read a stream from the peer in chunk sizes up to `maxChunkBytes` bytes.
    *
    * Last element read may be of size <= maxChunkBytes
    *
    * @param maxChunkBytes      Max size of each chunk of bytes read
    * @param timeout            A timeout for each chunk request. If chunk won't be read in given timeout,
    *                           the stream will fail with a [[java.nio.channels.InterruptedByTimeoutException]].
    * @return
    */
  def reads(
    maxChunkBytes: Int
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, ByteVector] =
    available(maxChunkBytes, timeout).flatMap {
      case None => Stream.empty
      case Some(read) => Stream.emit(read) ++ reads(maxChunkBytes, timeout)
    }

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
  ): Stream[Connection, ByteVector] =
    socket flatMap {_.readOnce(numBytes, timeout)}

  /** Returns a single-element stream containing the remote address of the peer. */
  def remoteAddress: Stream[Connection, InetSocketAddress] =
    socket flatMap { e => eval(e.remoteAddress) }


  /**
    * Write `bytes` to the peer. If `timeout` is provided
    * and the operation does not complete in the specified duration,
    * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
    */
  def write(
    bytes: ByteVector
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, Unit] =
    socket flatMap { e => eval(e.write(bytes, timeout)) }

  /** Like [[scalaz.stream.tcp.write]], but ignores the output. */
  def write_(
    bytes: ByteVector
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, Nothing] =
    write(bytes, timeout).drain

  /**
    * Write a stream of chunks to the peer. Emits a single `Unit` value for
    * each chunk written.
    * If timeout is specified, it is applied for each write operation of chunk supplied in `chunks`
    */
  def writes(
    chunks: Stream[Connection, ByteVector]
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, Unit] =
    chunks flatMap { bytes => write(bytes, timeout) }

  /** Like [[writes]], but ignores the output. */
  def writes_(
    chunks: Stream[Connection, ByteVector]
    , timeout: Option[FiniteDuration] = None
  ): Stream[Connection, Nothing] =
    writes(chunks, timeout).drain


  //////////////////////////////////////////////////////////////
  // End of API
  //////////////////////////////////////////////////////////////

  private object impl {

    trait Socket {

      /**
        * Read up to `maxBytes` from the peer.
        *
        * Emits once None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
        * before returning even single byte. Otherwise returns Some() with bytes read.
        *
        * If `timeout` is specified, then resulting stream will fail with `InterruptedByTimeoutException` if
        * read was not satisfied in given timeout.
        *
        * This may return None, as well when end of stream has been reached before timeout expired and any data
        * has been received.
        *
        */
      def available(
        maxBytes: Int
        , timeout: Option[FiniteDuration] = None
      ): Stream[Connection, Option[ByteVector]]

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
      ): Stream[Connection, ByteVector]


      /** Indicate that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
      def endOfInput: Task[Unit]

      /** Inidcates to peer, we are done writing **/
      def endOfOutput: Task[Unit]

      /** Close the connection corresponding to this `Socket`. */
      def close: Task[Unit]

      /** Ask for the remote address of the peer. */
      def remoteAddress: Task[InetSocketAddress]

      /** Ask for the local address of the socket. */
      def localAddress: Task[InetSocketAddress]

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
        , timeout: Option[FiniteDuration]
      ): Task[Unit]
    }


    def bindTo[A](skt: Socket, S: Strategy)(p: Stream[Connection, A]): Stream[Task, A] =
      p.translate(new (Connection ~> Task) {def apply[A0](cn: Connection[A0]) = cn.run(skt, S)})

    // Private usage only. Builds `Socket` of the Asynchronous channel.
    def asynchronous(channel: AsynchronousSocketChannel)(implicit S: Strategy): Socket = new Socket {
      def available(
        maxBytes: Int
        , timeout: Option[FiniteDuration] = None
      ): Stream[Connection, Option[ByteVector]] = {
        tcp.eval(Task.async[Option[ByteVector]] { cb =>
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
              catch {case e: Throwable => cb(Left(e))}
            }
            def failed(exc: Throwable, attachment: Void): Unit = S {cb(Left(exc))}
          }

          timeout.fold(channel.read(buf, null, handler)) { duration =>
            channel.read(buf, duration.length, duration.unit, null, handler)
          }

        })
      }


      /**
        * Read exactly `numBytes` from the peer in a single chunk.
        * If `timeout` is provided and no data arrives within the specified duration, the returned
        * `Process` fails with a [[InterruptedByTimeoutException]].
        *
        * Emits once, and if emit result's size is < numBytes, then End-Of-Stream state has been reached.
        */
      def readOnce(
        numBytes: Int
        , timeout: Option[FiniteDuration]
      ): Stream[Connection, ByteVector] = {
        def go(acc: ByteVector, rem: Option[FiniteDuration]): Stream[Connection, ByteVector] = {
          if (rem.exists(_ <= 0.millis)) Stream.fail(new InterruptedByTimeoutException())
          else {
            val start = timeout.fold(0l)(_ => System.currentTimeMillis())
            available(numBytes - acc.size, rem) flatMap {
              case None => Stream.emit(acc)
              case Some(read) =>
                if (read.size + acc.size == numBytes) Stream.emit(acc ++ read)
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


      def endOfInput: Task[Unit] =
        Task.delay {channel.shutdownInput()}

      def endOfOutput: Task[Unit] =
        Task.delay {channel.shutdownOutput()}

      def close: Task[Unit] =
        Task.delay {channel.close()}

      def remoteAddress: Task[InetSocketAddress] =
        Task.delay {
          // NB: This cast is safe because, from the javadoc:
          // "Where the channel is bound and connected to an Internet Protocol socket
          // address then the return value from this method is of type InetSocketAddress."
          channel.getRemoteAddress().asInstanceOf[InetSocketAddress]
        }

      def localAddress: Task[InetSocketAddress] =
        Task.delay {
          // NB: This cast is safe because, from the javadoc:
          // "Where the channel is bound and connected to an Internet Protocol socket
          // address then the return value from this method is of type InetSocketAddress."
          channel.getLocalAddress().asInstanceOf[InetSocketAddress]
        }

      def write(
        bytes: ByteVector
        , timeout: Option[FiniteDuration] = None
      ): Task[Unit] =
        writeOne(channel, bytes, timeout)

    }


    // implementation of write to the socket/channel
    // defined as recursion until all bytes has been written to the socket.
    // note that timeout applies over all attempts to write, and is reduced after every attempt made for time the attempt took.
    def writeOne(
      ch: AsynchronousSocketChannel
      , a: ByteVector
      , timeout: Option[FiniteDuration]
    )(implicit S: Strategy): Task[Unit] = {
      if (a.isEmpty) Task.now(())
      else {
        Task.async[(Int, Option[FiniteDuration])] { cb =>
          val now = timeout.fold(0l)(_ => System.currentTimeMillis())
          val handler = new CompletionHandler[Integer, Void] {
            def completed(result: Integer, attachment: Void): Unit = {
              val remains = timeout.map(dur => dur - (System.currentTimeMillis() - now).millis)
              S {cb(Right(result.intValue() -> remains))}
            }
            def failed(exc: Throwable, attachment: Void): Unit = S {cb(Left(exc))}
          }
          timeout match {
            case None => ch.write(a.toByteBuffer, null, handler)
            case Some(d) => ch.write(a.toByteBuffer, d.length, d.unit, null, handler)
          }
        }.flatMap { case (w, remains) =>
          if (w == a.length) Task.now(())
          else {
            if (remains.exists(_ <= 0.millis)) Task.fail(new InterruptedByTimeoutException())
            else writeOne(ch, a.drop(w), remains)
          }
        }
      }
    }

    /**
      * Heleper to get `socket` from `Stream[Connection,_]`
      * @return
      */
    def socket: Stream[Connection, Socket] =
      Stream.eval {
        new Connection[Socket] {
          def run(channel: Socket, S: Strategy) = Task.now(channel)
        }
      }


  }

}
