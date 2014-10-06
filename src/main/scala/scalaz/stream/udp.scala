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
  def eval_[A](a: Task[A]): Process[Connection,A] = Process.eval_(lift(a))

  /** Run a `Process[Task,A]` within a UDP-connected `Process`. */
  def lift[A](p: Process[Task,A]): Process[Connection,A] =
    p.translate(new (Task ~> Connection) { def apply[R](c: Task[R]) = lift(c) })

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

  /**
   * Send a single UDP [[Packet]] to the given destination.
   * number of bytes in the received. See `java.net.DatagramSocket#send`
   * for information about what exceptions may be raised within the returned stream.
   */
  def send(to: InetSocketAddress, bytes: ByteVector): Process[Connection,Unit] =
    ask.flatMap { socket => eval { Task.delay {
      val p = new DatagramPacket(bytes.toArray, 0, bytes.length, to.getAddress, to.getPort)
      socket.send(p)
    }}}

  /** Defined as `send(new InetSocketAddress(to, destinationPort), bytes)`. */
  def send(to: java.net.InetAddress, destinationPort: Int, bytes: ByteVector): Process[Connection,Unit] =
    send(new InetSocketAddress(to, destinationPort), bytes)

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
    Process.eval(Task.delay { new DatagramSocket(port) }).flatMap { socket =>
      Process.eval_ { Task.delay {
        socket.setReceiveBufferSize(receiveBufferSize)
        socket.setSendBufferSize(sendBufferSize)
        socket.setReuseAddress(reuseAddress)
      }} append bindTo(socket)(p) onComplete (Process.eval_(Task.delay(socket.close)))
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
