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

object socket {

  trait Socket[+A] {
    private[stream] def run(channel: AsynchronousSocketChannel): Task[A]
  }

  private[stream] def ask: Process[Socket,AsynchronousSocketChannel] =
    Process.eval { new Socket[AsynchronousSocketChannel] {
      def run(channel: AsynchronousSocketChannel) = Task.now(channel)
    }}

  def lift[A](t: Task[A]): Socket[A] =
    new Socket[A] { def run(channel: AsynchronousSocketChannel) = t }

  def lift[A](p: Process[Task,A]): Process[Socket,A] =
    p.translate(new (Task ~> Socket) { def apply[x](t: Task[x]) = socket.lift(t) })

  def eval[A](t: Task[A]): Process[Socket,A] = Process.eval(lift(t))

  def eval_[A](t: Task[A]): Process[Socket,Nothing] = Process.eval_(lift(t))

  def wye[A,B,C](a: Process[Socket,A], b: Process[Socket,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Socket,C] =
    ask flatMap { ch => lift { bindTo(ch)(a).wye(bindTo(ch)(b))(y)(S) } }

  def merge[A](a: Process[Socket,A], a2: Process[Socket,A])(implicit S:Strategy): Process[Socket,A] =
    wye(a,a2)(scalaz.stream.wye.merge)

  /**
   * Read exactly `numBytes` from the peer in a single chunk.
   * If `timeout` is provided and no data arrives within the specified duration, the returned
   * `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def read(numBytes: Int,
           timeout: Option[Duration] = None,
           allowPeerClosed: Boolean = false): Process[Socket,Option[ByteVector]] =
    available(numBytes, timeout, allowPeerClosed) flatMap {
      _ map { bs => if (bs.size == numBytes) Process.emit(Some(bs))
                    else read(numBytes - bs.size, timeout, allowPeerClosed).map(_.map(bs ++ _))
            } getOrElse (Process.emit(None))
    }

  def eof: Process[Socket,Nothing] =
    ask.evalMap { ch => lift(Task.delay {
      ch.shutdownOutput()
    })}.drain

  def reads(maxChunkBytes: Int,
            timeout: Option[Duration] = None,
            allowPeerClosed: Boolean = false): Process[Socket,ByteVector] =
    available(maxChunkBytes, timeout, allowPeerClosed).flatMap {
      case None => Process.halt
      case Some(bs) => Process.emit(bs) ++ reads(maxChunkBytes, timeout, allowPeerClosed)
    }

  /**
   * Read up to `maxBytes` from the peer. If `timeout` is provided
   * and the operation does not complete in the specified duration,
   * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def available(
        maxBytes: Int,
        timeout: Option[Duration] = None,
        allowPeerClosed: Boolean = false): Process[Socket,Option[ByteVector]] =
    Process.eval { new Socket[Option[ByteVector]] {
      def run(channel: AsynchronousSocketChannel) =
        handlePeerClosed[Option[ByteVector]](allowPeerClosed, None) { Task.async { cb =>
          val arr = new Array[Byte](maxBytes)
          val buf = ByteBuffer.wrap(arr)
          val handler = new CompletionHandler[Integer, Void] {
            def completed(result: Integer, attachment: Void): Unit =
              try {
                buf.flip()
                val bs = ByteVector(buf)
                if (result.intValue < 0) cb(right(None))
                else cb(right(Some(bs.take(result.intValue))))
              }
              catch { case e: Throwable => cb(left(e)) }
            def failed(exc: Throwable, attachment: Void): Unit = cb(left(exc))
          }
          timeout match {
            case None => channel.read(buf, null, handler)
            case Some(d) => channel.read(buf, d.length, d.unit, null, handler)
          }
        }}
    }}

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

  def stamp[A](supply: Process[Task,A]): Process[Socket,A] =
    lift(supply).take(1)

  def write(bytes: ByteVector,
            timeout: Option[Duration] = None,
            allowPeerClosed: Boolean = false): Process[Socket,Unit] =
    Process.eval { new Socket[Unit] {
      def run(channel: AsynchronousSocketChannel) =
        writeOne(channel, bytes, timeout, allowPeerClosed)
    }}

  def write_(bytes: ByteVector,
             timeout: Option[Duration] = None,
             allowPeerClosed: Boolean = false): Process[Socket,Nothing] =
    write(bytes, timeout, allowPeerClosed).drain

  def writes(chunks: Process[Socket,ByteVector],
             timeout: Option[Duration] = None,
             allowPeerClosed: Boolean = false): Process[Socket,Unit] =
    chunks.flatMap { bytes => write(bytes, timeout, allowPeerClosed) }

  def writes_(chunks: Process[Socket,ByteVector],
              timeout: Option[Duration] = None,
              allowPeerClosed: Boolean = false): Process[Socket,Nothing] =
    writes(chunks,timeout,allowPeerClosed).drain

  def lastWrites(chunks: Process[Socket,ByteVector],
                 timeout: Option[Duration] = None,
                 allowPeerClosed: Boolean = false): Process[Socket,Unit] =
    writes(chunks, timeout, allowPeerClosed) onComplete eof

  def lastWrites_(chunks: Process[Socket,ByteVector],
                  timeout: Option[Duration] = None,
                  allowPeerClosed: Boolean = false): Process[Socket,Nothing] =
    lastWrites(chunks, timeout, allowPeerClosed).drain

  private def writeOne(ch: AsynchronousSocketChannel, a: ByteVector,
                       timeout: Option[Duration],
                       allowPeerClosed: Boolean): Task[Unit] =
    handlePeerClosed(allowPeerClosed, ()) { Task.async[Int] { cb =>
      val handler = new CompletionHandler[Integer, Void] {
        def completed(result: Integer, attachment: Void): Unit = cb(right(result))
        def failed(exc: Throwable, attachment: Void): Unit = cb(left(exc))
      }
      timeout match {
        case None => ch.write(a.toByteBuffer, null, handler)
        case Some(d) => ch.write(a.toByteBuffer, d.length, d.unit, null, handler)
      }
    }.flatMap { w =>
      if (w == a.length) Task.now(())
      else writeOne(ch, a.drop(w), timeout, allowPeerClosed)
    }}

  /**
   * Process that connects to remote server (TCP) and provides one exchange representing connection to that server
   *
   * @param to              Address of remote server
   * @param reuseAddress    whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param sndBufferSize   size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
   * @param rcvBufferSize   size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @param keepAlive       whether keep-alive on tcp is used (@see [[java.net.StandardSocketOptions.SO_KEEPALIVE]])
   * @param noDelay         whether tcp no-delay flag is set  (@see [[java.net.StandardSocketOptions.TCP_NODELAY]])
   * @return
   */
  def connect[A](to: InetSocketAddress,
                 reuseAddress: Boolean = true,
                 sndBufferSize: Int = 256 * 1024,
                 rcvBufferSize: Int = 256 * 1024,
                 keepAlive: Boolean = false,
                 noDelay: Boolean = false)(output: Process[Socket,A])(implicit AG: AsynchronousChannelGroup):
              Process[Task,A] = {

    def setup: Task[AsynchronousSocketChannel] = Task.delay {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sndBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def connect(ch: AsynchronousSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        ch.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit = cb(right(ch))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(left(rsn))
        })
      }

    Process.await(setup flatMap connect) { ch =>
      bindTo(ch)(output).onComplete(Process.eval_(Task.delay(ch.close)))
    }
  }

  /**
   * Process that binds to supplied address and provides accepted exchanges
   * representing incoming connections (TCP) with remote clients
   * When this process terminates all the incoming exchanges will terminate as well
   * @param bind               address to which this process has to be bound
   * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param rcvBufferSize      size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @return
   */
  def server[A](bind: InetSocketAddress,
                concurrentRequests: Int,
                maxQueued: Int = 0,
                reuseAddress: Boolean = true,
                rcvBufferSize: Int = 256 * 1024)(handler: Process[Socket,A])(
                implicit AG: AsynchronousChannelGroup,
                         S: Strategy): Process[Task, Throwable \/ A] = {

    require(concurrentRequests > 0, "concurrent requests must be positive")

    def setup: Task[AsynchronousServerSocketChannel] =
      Task.delay {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
        ch.bind(bind)
        ch
      }

    def accept(sch: AsynchronousServerSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit = cb(right(result))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(left(rsn))
        })
      }

    def processRequest(c: AsynchronousSocketChannel) =
      bindTo(c)(handler).map(right).onHalt {
        case Cause.Error(err) => Process.eval_(Task.delay(c.close)) onComplete Process.emit(left(err))
        case _ => Process.eval_(Task.delay(c.close))
      }

    if (concurrentRequests > 1)
      nondeterminism.njoin(concurrentRequests, maxQueued) {
        Process.eval(setup) flatMap { sch =>
          Process.repeatEval(accept(sch)).map(processRequest).onComplete (Process.eval_(Task.delay { sch.close }))
        }
      }
    else
      Process.eval(setup) flatMap { sch =>
        Process.repeatEval(accept(sch)).flatMap(processRequest).onComplete (Process.eval_(Task.delay { sch.close }))
      }
  }

  private def bindTo[A](c: AsynchronousSocketChannel)(p: Process[Socket,A]): Process[Task,A] =
    p.translate(new (Socket ~> Task) { def apply[A](s: Socket[A]) = s.run(c) })

  lazy val DefaultAsynchronousChannelGroup = {
    val idx = new AtomicInteger(0)
    AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
      Runtime.getRuntime.availableProcessors() * 2 max 2
      , new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"scalaz-stream-nio-${idx.incrementAndGet() }")
          t.setDaemon(true)
          t
        }
      }
    )
  }

  object syntax {
    implicit class Syntax[+A](self: Process[Socket,A]) {
      def wye[B,C](p2: Process[Socket,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Socket,C] =
        socket.wye(self, p2)(y)
      def merge[A2>:A](p2: Process[Socket,A2])(implicit S:Strategy): Process[Socket,A2] =
        socket.merge(self, p2)
    }
  }
}
