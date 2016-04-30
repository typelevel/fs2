package fs2.io.udp

import java.net.{SocketAddress, DatagramPacket, DatagramSocket}
import java.util.concurrent.atomic.AtomicBoolean

import fs2._
import Stream._



trait Socket[F[_]] {

  /**
    * Reads packets received on this udp socket.
    *
    * Note that this allocates a thread that will read Packets as they will be recieved by
    * this socket. Once the packet is received, then further evaluation is forked to
    * Strategy associated with supplied `F`
    *
    * Note that multiple `reads` may execute at same time, causing each evaluation to receive fair
    * amount of messages.
    *
    * @return
    */
  def reads:Stream[F,Packet]


  /**
    * Write a single packet on this UDP socket.
    *
    * @param packet  Packet to write
    */
  def write(packet:Packet):F[Unit]

  /**
    * Writes supplied packets to this socket
    *
    * @param packets
    */
  def writes(packets:Stream[F,Packet]):Stream[F,Unit]

  /** Ask for the local address of the socket. */
  def localAddress: F[SocketAddress]

  /** closes this socket */
  def close:F[Unit]

}


object Socket {

  def listen[F[_],A](
    bind: SocketAddress
    , queueMax:Int
    , receiveBufferSize: Int
    , sendBufferSize: Int
    , reuseAddress: Boolean
  )( implicit  F:Async[F] ): Stream[F,Socket[F]] = {
    Stream.bracket(F.suspend(new DatagramSocket(bind)))(
      ch => Stream.bracket(mkSocket(bind,queueMax,ch))(s => emit(s), _.close)
      , ch => F.suspend(ch.close())
    )
  }



  def mkSocket[F[_]](bind:SocketAddress, queueMax:Int, dgs:DatagramSocket)(implicit F:Async[F]):F[Socket[F]] = {
    F.bind(async.signalOf[F,Boolean](false)) { stopRead =>
    F.map(async.boundedQueue[F,Packet](queueMax)) { readQueue =>
      val closedNow = new AtomicBoolean(false)

      val reader = new Runnable {
        def run(): Unit = {
          if (!closedNow.get()) {

          }
        }
      }

      val t = new Thread(reader, s"fs2-udp-$bind")
      t.setDaemon(true)
      t.run()


      def write0(packet: Packet): F[Unit] = F.suspend {
        val data = new DatagramPacket(packet.bytes.values, packet.bytes.size, packet.remote)
        dgs.send(data)
      }

      def close0: F[Unit] = {
        F.bind(F.suspend(closedNow.set(true))) { _ =>
        F.bind(stopRead.set(true)) { _ =>
          if (dgs.isClosed) F.pure(())
          else F.suspend(dgs.close())

        }}

      }




      new Socket[F] {
        def localAddress: F[SocketAddress] =
          F.suspend(dgs.getLocalSocketAddress)

        def reads: Stream[F, Packet] =
          readQueue.dequeue.interruptWhen(stopRead)

        def write(packet: Packet): F[Unit] =
          write0(packet)

        def writes(packets: Stream[F, Packet]): Stream[F, Unit] =
          packets.flatMap(p => eval(write(p)))

        def close: F[Unit] =
          close0
      }
    }
  }}


}
