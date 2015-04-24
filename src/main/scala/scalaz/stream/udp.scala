package scalaz.stream

import java.net.{DatagramPacket,DatagramSocket,InetSocketAddress}
import scala.concurrent.duration.Duration
import scalaz.concurrent.{Task,Strategy}
import scalaz.~>
import scodec.bits.ByteVector

object udp {

  /** A UDP packet, consisting of an origin IP and port, and a `ByteVector` payload. */
  case class Packet(origin: InetSocketAddress, bytes: ByteVector)

  /** Evaluate a `Task` within a UDP-connected `Process`. */
  def eval[A](a: Task[A]): Process[Connection,A] = Process.eval(lift(a))

  /** Evaluate and discard the result of a `Task` within a UDP-connected `Process`. */
  def eval_[A](a: Task[A]): Process[Connection,Nothing] = Process.eval_(lift(a))

  /** Run a `Process[Task,A]` within a UDP-connected `Process`. */
  def lift[A](p: Process[Task,A]): Process[Connection,A] =
    p.translate(new (Task ~> Connection) { def apply[R](c: Task[R]) = lift(c) })

  /**
   * Open a UDP socket on the specified port and run the given process `p`.
   *
   * @param packetSize Determines the size of the `Array[Byte]` allocated for the
   *                   receiving `java.net.DatagramPacket`
   * @param timeout optional timeout for each receive operation. A `java.net.SocketTimeoutException`
   *                is raised in the stream if any receive take more than this amount of time
   */
  def listen[A](port: Int,
                receiveBufferSize: Int = 1024 * 32,
                sendBufferSize: Int = 1024 * 32,
                reuseAddress: Boolean = true)(p: Process[Connection,A]): Process[Task,A] =
    Process.eval(Task.delay { new DatagramSocket(null) }).flatMap { socket =>
      Process.eval_ { Task.delay {
        socket.setReceiveBufferSize(receiveBufferSize)
        socket.setSendBufferSize(sendBufferSize)
        socket.setReuseAddress(reuseAddress)
        socket.bind(new InetSocketAddress(port))
      }} append bindTo(socket)(p) onComplete (Process.eval_(Task.delay(socket.close)))
    }

  def merge[A](a: Process[Connection,A], a2: Process[Connection,A])(implicit S: Strategy): Process[Connection,A] =
    wye(a,a2)(scalaz.stream.wye.merge)

  /** Returns a single-element stream containing the local address of the bound socket. */
  def localAddress: Process[Connection,InetSocketAddress] =
    ask.flatMap { d => eval(Task.delay(d.getLocalSocketAddress.asInstanceOf[InetSocketAddress])) }

  /**
   * Receive a single UDP [[Packet]]. `maxPacketSize` controls the maximum
   * number of bytes in the received. See `java.net.DatagramSocket#receive`
   * for information about what exceptions may be raised within the returned stream.
   */
  def receive(maxPacketSize: Int, timeout: Option[Duration] = None): Process[Connection,Packet] =
    ask.flatMap { socket => eval { Task.delay {
      val oldTimeout = socket.getSoTimeout
      timeout.foreach(d => socket.setSoTimeout(d.toMillis.toInt))
      val p = new DatagramPacket(new Array[Byte](maxPacketSize), maxPacketSize)
      socket.receive(p)
      timeout.foreach { _ => socket.setSoTimeout(oldTimeout) }
      Packet(new InetSocketAddress(p.getAddress, p.getPort), ByteVector(p.getData).take(p.getLength))
    }}}

  /** Defined as `receive(maxPacketSize, timeout).repeat. */
  def receives(maxPacketSize: Int, timeout: Option[Duration] = None): Process[Connection,Packet] =
    receive(maxPacketSize, timeout).repeat

  /**
   * Send a single UDP [[Packet]] to the given destination. Returns a single `Unit`.
   * See `java.net.DatagramSocket#send` for information about what exceptions may
   * be raised within the returned stream.
   */
  def send(to: InetSocketAddress, bytes: ByteVector): Process[Connection,Unit] =
    ask.flatMap { socket => eval { Task.delay {
      val p = new DatagramPacket(bytes.toArray, 0, bytes.length, to.getAddress, to.getPort)
      socket.send(p)
    }}}

  /** Defined as `send(new InetSocketAddress(to, destinationPort), bytes)`. */
  def send(to: java.net.InetAddress, destinationPort: Int, bytes: ByteVector): Process[Connection,Unit] =
    send(new InetSocketAddress(to, destinationPort), bytes)

  /** Defined as `chunks.flatMap { bytes => udp.send(to, bytes) }` */
  def sends(to: InetSocketAddress, chunks: Process[Connection,ByteVector]): Process[Connection,Unit] =
   chunks.flatMap { bytes => send(to, bytes) }

  /** Defined as `chunks.flatMap { bytes => udp.send(to, bytes) }.drain` */
  def sends_(to: InetSocketAddress, chunks: Process[Connection,ByteVector]): Process[Connection,Nothing] =
    sends(to, chunks).drain

  /** Defined as `sends(new InetSocketAddress(to, destinationPort), chunks)`. */
  def sends(to: java.net.InetAddress, destinationPort: Int, chunks: Process[Connection,ByteVector]): Process[Connection,Unit] =
    sends(new InetSocketAddress(to, destinationPort), chunks)

  /** Defined as `sends(new InetSocketAddress(to, destinationPort), chunks).drain`. */
  def sends_(to: java.net.InetAddress, destinationPort: Int, chunks: Process[Connection,ByteVector]): Process[Connection,Nothing] =
    sends(to, destinationPort, chunks).drain

  def wye[A,B,C](a: Process[Connection,A], b: Process[Connection,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Connection,C] =
    ask flatMap { ch => lift { bindTo(ch)(a).wye(bindTo(ch)(b))(y)(S) } }

  object syntax {
    implicit class Syntax[+A](self: Process[Connection,A]) {
      def wye[B,C](p2: Process[Connection,B])(y: Wye[A,B,C])(implicit S:Strategy): Process[Connection,C] =
        udp.wye(self, p2)(y)
      def merge[A2>:A](p2: Process[Connection,A2])(implicit S:Strategy): Process[Connection,A2] =
        udp.merge(self, p2)
    }
    implicit class ChannelSyntax[A](self: Process[Connection,A]) {
      def through[B](chan: Channel[Task,A,B]): Process[Connection,B] =
        self zip (lift(chan)) evalMap { case (a,f) => lift(f(a)) }
      def to(chan: Sink[Task,A]): Process[Connection,Unit] = through(chan)
    }
  }

  private def ask: Process[Connection,DatagramSocket] =
    Process.eval { new Connection[DatagramSocket] {
      def apply(s: DatagramSocket) = Task.now(s)
    }}

  private def bindTo[A](socket: DatagramSocket)(p: Process[Connection,A]): Process[Task,A] =
    p.translate (new (Connection ~> Task) { def apply[R](c: Connection[R]) = c(socket) })

  trait Connection[+A] {
    private[stream] def apply(socket: DatagramSocket): Task[A]
  }

  private def lift[A](t: Task[A]): Connection[A] = new Connection[A] {
    def apply(s: DatagramSocket) = t
  }
}
