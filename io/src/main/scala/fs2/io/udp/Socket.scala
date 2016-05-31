package fs2.io.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel

import fs2._

sealed trait Socket[F[_]] {

  /**
   * Reads a single packet from this udp socket.
   */
  def read: F[Packet]

  /**
   * Reads packets received from this udp socket.
   *
   * Note that multiple `reads` may execute at same time, causing each evaluation to receive fair
   * amount of messages.
   *
   * @return stream of packets
   */
  def reads: Stream[F,Packet]

  /**
   * Write a single packet to this udp socket.
   *
   * @param packet  Packet to write
   */
  def write(packet: Packet): F[Unit]

  /**
   * Writes supplied packets to this udp socket.
   */
  def writes: Sink[F,Packet]

  /** Returns the local address of this udp socket if the socket is bound. */
  def localAddress: F[Option[SocketAddress]]

  /** Closes this socket. */
  def close: F[Unit]
}

object Socket {

  private[fs2] def mkSocket[F[_]](channel: DatagramChannel, description: String)(implicit AG: AsynchronousSocketGroup, F: Async[F], FR: Async.Run[F]): F[Socket[F]] = F.delay {
    new Socket[F] {
      private val ctx = AG.register(channel)

      private def invoke(f: => Unit): Unit =
        FR.unsafeRunAsyncEffects(F.delay(f))(_ => ())

      def localAddress: F[Option[SocketAddress]] =
        F.delay(Option(channel.socket.getLocalSocketAddress))

      def read: F[Packet] = F.async(cb => F.delay { AG.read(ctx, result => invoke(cb(result))) })

      def reads: Stream[F, Packet] =
        Stream.repeatEval(read)

      def write(packet: Packet): F[Unit] =
        F.async(cb => F.delay {
          AG.write(ctx, packet, _ match { case Some(t) => invoke(cb(Left(t))); case None => invoke(cb(Right(()))) })
        })

      def writes: Sink[F, Packet] =
        _.flatMap(p => Stream.eval(write(p)))

      def close: F[Unit] = F.delay { AG.close(ctx) }

      override def toString = s"Socket($description)"
    }
  }
}

