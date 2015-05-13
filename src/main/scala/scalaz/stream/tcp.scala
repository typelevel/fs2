package scalaz.stream

import java.io.IOException
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, AsynchronousServerSocketChannel, CompletionHandler}
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadFactory
import scala.concurrent.duration._
import scalaz.concurrent.{Strategy,Task}
import scalaz.\/._
import scalaz.\/
import scalaz.~>
import scodec.bits.ByteVector

object tcp {

  /**
   * A `Task[A]` which has access to an `Socket`, for reading/writing from
   * some network resource.
   */
  trait Connection[+A] {
    private[stream] def run(channel: Socket, S: Strategy): Task[A]
  }

  trait Socket {

    /**
     * Read up to `maxBytes` from the peer. If `timeout` is provided
     * and the operation does not complete in the specified duration,
     * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
     */
    def available(
          maxBytes: Int,
          timeout: Option[Duration] = None,
          allowPeerClosed: Boolean = false): Task[Option[ByteVector]]

    /** Indicate to the peer that we are done writing. */
    def eof: Task[Unit]

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
     */
    def write(bytes: ByteVector,
              timeout: Option[Duration] = None,
              allowPeerClosed: Boolean = false): Task[Unit]
  }

  private def asynchronous(channel: AsynchronousSocketChannel)(implicit S: Strategy): Socket = new Socket {

    def available(maxBytes: Int,
                  timeout: Option[Duration] = None,
                  allowPeerClosed: Boolean = false): Task[Option[ByteVector]] =
      handlePeerClosed[Option[ByteVector]](allowPeerClosed, None) { Task.async { cb =>
        val arr = new Array[Byte](maxBytes)
        val buf = ByteBuffer.wrap(arr)
        val handler = new CompletionHandler[Integer, Void] {
          def completed(result: Integer, attachment: Void): Unit = S {
            try {
              buf.flip()
              val bs = ByteVector.view(buf) // no need to copy since `buf` is not reused
              if (result.intValue < 0) cb(right(None))
              else cb(right(Some(bs.take(result.intValue))))
            }
            catch { case e: Throwable => cb(left(e)) }
          }
          def failed(exc: Throwable, attachment: Void): Unit = S { cb(left(exc)) }
        }
        timeout match {
          case None => channel.read(buf, null, handler)
          case Some(d) => channel.read(buf, d.length, d.unit, null, handler)
        }
      }}

    def eof: Task[Unit] =
      Task.delay { channel.shutdownOutput() }

    def close: Task[Unit] =
      Task.delay { channel.close() }

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

    def write(bytes: ByteVector,
              timeout: Option[Duration] = None,
              allowPeerClosed: Boolean = false): Task[Unit] =
      writeOne(channel, bytes, timeout, allowPeerClosed, S)

  }

  private[stream] def ask: Process[Connection,Socket] =
    Process.eval { new Connection[Socket] {
      def run(channel: Socket, S: Strategy) = Task.now(channel)
    }}

  private[stream] def strategy: Process[Connection,Strategy] =
    Process.eval { new Connection[Strategy] {
      def run(channel: Socket, S: Strategy) = Task.now(S)
    }}

  private def lift[A](t: Task[A]): Connection[A] =
    new Connection[A] { def run(channel: Socket, S: Strategy) = t }

  def local[A](f: Socket => Socket)(p: Process[Connection,A]): Process[Connection,A] =
    for {
      e <- ask
      s <- strategy
      a <- lift { bindTo(f(e), s)(p) }
    } yield a

  /** Lift a regular `Process` to operate in the context of a `Connection`. */
  def lift[A](p: Process[Task,A]): Process[Connection,A] =
    p.translate(new (Task ~> Connection) { def apply[x](t: Task[x]) = tcp.lift(t) })

  /** Evaluate and emit the result of `t`. */
  def eval[A](t: Task[A]): Process[Connection,A] = Process.eval(lift(t))

  /** Evaluate and ignore the result of `t`. */
  def eval_[A](t: Task[A]): Process[Connection,Nothing] = Process.eval_(lift(t))

  def wye[A,B,C](a: Process[Connection,A], b: Process[Connection,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Connection,C] =
    ask flatMap { ch => lift { bindTo(ch, S)(a).wye(bindTo(ch, S)(b))(y)(S) } }

  def merge[A](a: Process[Connection,A], a2: Process[Connection,A])(implicit S:Strategy): Process[Connection,A] =
    wye(a,a2)(scalaz.stream.wye.merge)

  /**
   * Read up to `numBytes` from the peer.
   * If `timeout` is provided and no data arrives within the specified duration, the returned
   * `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   * If `allowPeerClosed` is `true`, abrupt termination by the peer is converted to `None`
   * rather than being raised as an exception.
   */
  def available(
        maxBytes: Int,
        timeout: Option[Duration] = None,
        allowPeerClosed: Boolean = false): Process[Connection,Option[ByteVector]] =
    ask flatMap { e => eval(e.available(maxBytes, timeout, allowPeerClosed)) }

  /**
   * Read exactly `numBytes` from the peer in a single chunk.
   * If `timeout` is provided and no data arrives within the specified duration, the returned
   * `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def read(numBytes: Int,
           timeout: Option[Duration] = None,
           allowPeerClosed: Boolean = false): Process[Connection,Option[ByteVector]] =
    available(numBytes, timeout, allowPeerClosed) flatMap {
      _ map { bs => if (bs.size == numBytes) Process.emit(Some(bs))
                    else read(numBytes - bs.size, timeout, allowPeerClosed).map(_.map(bs ++ _))
            } getOrElse (Process.emit(None))
    }

  /** Returns a single-element stream containing the remote address of the peer. */
  def remoteAddress: Process[Connection,InetSocketAddress] =
    ask.flatMap { e => eval(e.remoteAddress) }

  /** Returns a single-element stream containing the local address of the connection. */
  def localAddress: Process[Connection,InetSocketAddress] =
    ask.flatMap { e => eval(e.localAddress) }

  /** Indicate to the peer that we are done writing. */
  def eof: Process[Connection,Nothing] =
    ask.flatMap { e => eval(e.eof) }.drain

  /** Read a stream from the peer in chunk sizes up to `maxChunkBytes` bytes. */
  def reads(maxChunkBytes: Int,
            timeout: Option[Duration] = None,
            allowPeerClosed: Boolean = false): Process[Connection,ByteVector] =
    available(maxChunkBytes, timeout, allowPeerClosed).flatMap {
      case None => Process.halt
      case Some(bs) => Process.emit(bs) ++ reads(maxChunkBytes, timeout, allowPeerClosed)
    }

  // Java unfortunately lacks a proper exception type for these cases
  private def handlePeerClosed[A](allowPeerClosed: Boolean, default: A)(t: Task[A]): Task[A] =
    if (allowPeerClosed) t.attempt.flatMap {
      _.fold(
        { case e: IOException if e.getMessage == "Broken pipe"
                              || e.getMessage == "Connection reset by peer" => Task.now(default)
          case t => Task.fail(t)
        },
        Task.now
      )
    }
    else t

  /**
   * Write `bytes` to the peer. If `timeout` is provided
   * and the operation does not complete in the specified duration,
   * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def write(bytes: ByteVector,
            timeout: Option[Duration] = None,
            allowPeerClosed: Boolean = false): Process[Connection,Unit] =
    ask.flatMap { e => eval(e.write(bytes, timeout, allowPeerClosed)) }

  /** Like [[scalaz.stream.tcp.write]], but ignores the output. */
  def write_(bytes: ByteVector,
             timeout: Option[Duration] = None,
             allowPeerClosed: Boolean = false): Process[Connection,Nothing] =
    write(bytes, timeout, allowPeerClosed).drain

  /**
   * Write a stream of chunks to the peer. Emits a single `Unit` value for
   * each chunk written.
   */
  def writes(chunks: Process[Connection,ByteVector],
             timeout: Option[Duration] = None,
             allowPeerClosed: Boolean = false): Process[Connection,Unit] =
    chunks.flatMap { bytes => write(bytes, timeout, allowPeerClosed) }

  /** Like [[scalaz.stream.tcp.writes]], but ignores the output. */
  def writes_(chunks: Process[Connection,ByteVector],
              timeout: Option[Duration] = None,
              allowPeerClosed: Boolean = false): Process[Connection,Nothing] =
    writes(chunks,timeout,allowPeerClosed).drain


  /** Defined as `tcp.writes(chunks,timeout,allowPeerClosed) onComplete eof`. */
  def lastWrites(chunks: Process[Connection,ByteVector],
                 timeout: Option[Duration] = None,
                 allowPeerClosed: Boolean = false): Process[Connection,Unit] =
    writes(chunks, timeout, allowPeerClosed) onComplete eof

  /** Like [[scalaz.stream.tcp.lastWrites]], but ignores the output. */
  def lastWrites_(chunks: Process[Connection,ByteVector],
                  timeout: Option[Duration] = None,
                  allowPeerClosed: Boolean = false): Process[Connection,Nothing] =
    lastWrites(chunks, timeout, allowPeerClosed).drain

  private def writeOne(ch: AsynchronousSocketChannel, a: ByteVector,
                       timeout: Option[Duration],
                       allowPeerClosed: Boolean,
                       S: Strategy): Task[Unit] =
    if (a.isEmpty) Task.now(())
    else handlePeerClosed(allowPeerClosed, ()) { Task.async[Int] { cb =>
      val handler = new CompletionHandler[Integer, Void] {
        def completed(result: Integer, attachment: Void): Unit = S { cb(right(result)) }
        def failed(exc: Throwable, attachment: Void): Unit = S { cb(left(exc)) }
      }
      timeout match {
        case None => ch.write(a.toByteBuffer, null, handler)
        case Some(d) => ch.write(a.toByteBuffer, d.length, d.unit, null, handler)
      }
    }.flatMap { w =>
      if (w == a.length) Task.now(())
      else writeOne(ch, a.drop(w), timeout, allowPeerClosed, S)
    }}

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
  def connect[A](to: InetSocketAddress,
                 reuseAddress: Boolean = true,
                 sendBufferSize: Int = 256 * 1024,
                 receiveBufferSize: Int = 256 * 1024,
                 keepAlive: Boolean = false,
                 noDelay: Boolean = false)(output: Process[Connection,A])(
                 implicit AG: AsynchronousChannelGroup, S: Strategy): Process[Task,A] = {

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
            cb(right(ch))

          def failed(rsn: Throwable, attachment: Void): Unit =
            cb(left(rsn))
        })
      }

    Process.await(setup flatMap connect) { ch =>
      val chan = asynchronous(ch)
      bindTo(chan, S)(output).onComplete(Process.eval_(chan.close))
    }
  }

  /**
   * Process that binds to supplied address and handles incoming TCP connections
   * using the specified handler. The stream of handler results is returned,
   * along with any errors. The outer stream scopes the lifetime of the server socket.
   * When the returned process terminates, all open connections will terminate as well.
   *
   * @param bind               address to which this process has to be bound
   * @param concurrentRequests the number of requests that may be processed simultaneously, must be positive
   * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param receiveBufferSize  size of receive buffer (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   */
  def server[A](bind: InetSocketAddress,
                concurrentRequests: Int,
                maxQueued: Int = 0,
                reuseAddress: Boolean = true,
                receiveBufferSize: Int = 256 * 1024)(handler: Process[Connection,A])(
                implicit AG: AsynchronousChannelGroup,
                         S: Strategy): Process[Task, Process[Task, Throwable \/ A]] = {

    require(concurrentRequests > 0, "concurrent requests must be positive")

    def setup: Process[Task, AsynchronousServerSocketChannel] = Process.eval {
      Task.delay {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.bind(bind)
      }
    }

    def accept(sch: AsynchronousServerSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit =
            S { cb(right(result)) }

          def failed(rsn: Throwable, attachment: Void): Unit =
            S { cb(left(rsn)) }
        })
      }

    def processRequest(c: AsynchronousSocketChannel) = {
      val chan = asynchronous(c)
      bindTo(chan, S)(handler).map(right).onHalt {
        case Cause.Error(err) => Process.eval_(chan.close) onComplete Process.emit(left(err))
        case cause => Process.eval_(chan.close).causedBy(cause)
      }
    }

    if (concurrentRequests > 1) setup map { sch =>
      nondeterminism.njoin(concurrentRequests, maxQueued) {
        Process.repeatEval(accept(sch)).map(processRequest)
      }.onComplete(Process.eval_(Task.delay { sch.close }))
    }
    else
      setup map { sch =>
        Process.repeatEval(accept(sch)).flatMap(processRequest)
               .onComplete (Process.eval_(Task.delay { sch.close }))
      }
  }

  private def bindTo[A](c: Socket, S: Strategy)(p: Process[Connection,A]): Process[Task,A] =
    p.translate(new (Connection ~> Task) { def apply[A](s: Connection[A]) = s.run(c, S) })

  lazy val DefaultAsynchronousChannelGroup = {
    val idx = new AtomicInteger(0)
    AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
      Runtime.getRuntime.availableProcessors() * 2 max 2
      , new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"scalaz-stream-tcp-${idx.incrementAndGet() }")
          t.setDaemon(true)
          t
        }
      }
    )
  }

  object syntax {
    implicit class Syntax[+A](self: Process[Connection,A]) {
      def wye[B,C](p2: Process[Connection,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Connection,C] =
        tcp.wye(self, p2)(y)
      def merge[A2>:A](p2: Process[Connection,A2])(implicit S:Strategy): Process[Connection,A2] =
        tcp.merge(self, p2)
    }
    implicit class ChannelSyntax[A](self: Process[Connection,A]) {
      def through[B](chan: Channel[Task,A,B]): Process[Connection,B] =
        self zip (lift(chan)) evalMap { case (a,f) => lift(f(a)) }
      def to(chan: Sink[Task,A]): Process[Connection,Unit] = through(chan)
    }
  }
}
