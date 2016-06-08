package fs2
package io
package udp

import scala.collection.mutable.{Queue=>MutableQueue}

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException,ClosedChannelException,DatagramChannel,Selector,SelectionKey}
import java.util.concurrent.CountDownLatch

/**
 * Supports read/write operations on an arbitrary number of UDP sockets using a shared selector thread.
 *
 * Each `AsynchronousSocketGroup` is assigned a single daemon thread that performs all read/write operations.
 */
sealed trait AsynchronousSocketGroup {
  private[udp] type Context
  private[udp] def register(channel: DatagramChannel): Context
  private[udp] def read(ctx: Context, cb: Either[Throwable,Packet] => Unit): Unit
  private[udp] def write(ctx: Context, packet: Packet, cb: Option[Throwable] => Unit): Unit
  private[udp] def close(ctx: Context): Unit

  /**
   * Shuts down the daemon thread used for selection and rejects all future register/read/write requests.
   */
  def close: Unit
}

object AsynchronousSocketGroup {

  def apply(): AsynchronousSocketGroup = new AsynchronousSocketGroup {

    class Attachment(
      readers: MutableQueue[Either[Throwable,Packet] => Unit] = MutableQueue(),
      writers: MutableQueue[(Packet,Option[Throwable] => Unit)] = MutableQueue()
    ) {

      def hasReaders: Boolean = readers.nonEmpty

      def peekReader: Option[Either[Throwable,Packet] => Unit] = {
        if (readers.isEmpty) None
        else Some(readers.head)
      }

      def dequeueReader: Option[Either[Throwable,Packet] => Unit] = {
        if (readers.isEmpty) None
        else Some(readers.dequeue())
      }

      def queueReader(reader: Either[Throwable,Packet] => Unit): Unit = {
        if (closed) reader(Left(new ClosedChannelException))
        else {
          readers += reader
          ()
        }
      }

      def hasWriters: Boolean = writers.nonEmpty

      def peekWriter: Option[(Packet,Option[Throwable] => Unit)] = {
        if (writers.isEmpty) None
        else Some(writers.head)
      }

      def dequeueWriter: Option[(Packet,Option[Throwable] => Unit)] = {
        if (writers.isEmpty) None
        else Some(writers.dequeue())
      }

      def queueWriter(writer: (Packet,Option[Throwable] => Unit)): Unit = {
        if (closed) writer._2(Some(new ClosedChannelException))
        else {
          writers += writer
          ()
        }
      }

      def close: Unit = {
        readers.foreach { cb => cb(Left(new ClosedChannelException)) }
        readers.clear
        writers.foreach { case (_, cb) => cb(Some(new ClosedChannelException)) }
        writers.clear
      }
    }

    type Context = SelectionKey

    private val selector = Selector.open()
    private val closeLock = new Object
    @volatile private var closed = false
    private val pendingThunks: MutableQueue[() => Unit] = MutableQueue()
    private val readBuffer = ByteBuffer.allocate(1 << 16)

    override def register(channel: DatagramChannel): Context = {
      var key: SelectionKey = null
      val latch = new CountDownLatch(1)
      onSelectorThread {
        channel.configureBlocking(false)
        val attachment = new Attachment()
        key = channel.register(selector, 0, attachment)
        latch.countDown
      } { latch.countDown }
      latch.await
      if (key eq null) throw new ClosedChannelException()
      key
    }

    override def read(key: SelectionKey, cb: Either[Throwable,Packet] => Unit): Unit = {
      onSelectorThread {
        val channel = key.channel.asInstanceOf[DatagramChannel]
        val attachment = key.attachment.asInstanceOf[Attachment]
        if (attachment.hasReaders) {
          attachment.queueReader(cb)
        } else {
          if (!read1(key, channel, attachment, cb)) {
            attachment.queueReader(cb)
            try { key.interestOps(key.interestOps | SelectionKey.OP_READ); () }
            catch { case t: CancelledKeyException => /* Ignore; key was closed */ }
          }
        }
      } { cb(Left(new ClosedChannelException)) }
    }

    private def read1(key: SelectionKey, channel: DatagramChannel, attachment: Attachment, reader: Either[Throwable,Packet] => Unit): Boolean = {
      readBuffer.clear
      val src = channel.receive(readBuffer).asInstanceOf[InetSocketAddress]
      if (src eq null) {
        false
      } else {
        readBuffer.flip
        val bytes = Array.ofDim[Byte](readBuffer.remaining)
        readBuffer.get(bytes)
        readBuffer.clear
        reader(Right(new Packet(src, Chunk.bytes(bytes))))
        true
      }
    }

    override def write(key: SelectionKey, packet: Packet, cb: Option[Throwable] => Unit): Unit = {
      onSelectorThread {
        val channel = key.channel.asInstanceOf[DatagramChannel]
        val attachment = key.attachment.asInstanceOf[Attachment]
        if (attachment.hasWriters) {
          attachment.queueWriter((packet, cb))
        } else {
          if (!write1(key, channel, attachment, packet, cb)) {
            attachment.queueWriter((packet, cb))
            try { key.interestOps(key.interestOps | SelectionKey.OP_WRITE); () }
            catch { case t: CancelledKeyException => /* Ignore; key was closed */ }
          }
        }
      } { cb(Some(new ClosedChannelException)) }
    }

    private def write1(key: SelectionKey, channel: DatagramChannel, attachment: Attachment, p: Packet, cb: Option[Throwable] => Unit): Boolean = {
      try {
        val sent = channel.send(ByteBuffer.wrap(p.bytes.toArray), p.remote)
        if (sent > 0) {
          cb(None)
          true
        } else {
          false
        }
      } catch {
        case e: IOException =>
          cb(Some(e))
          true
      }
    }

    override def close(key: SelectionKey): Unit = {
      onSelectorThread {
        val channel = key.channel.asInstanceOf[DatagramChannel]
        val attachment = key.attachment.asInstanceOf[Attachment]
        key.cancel
        channel.close
        attachment.close
      } { () }
    }

    override def close: Unit = {
      closeLock.synchronized { closed = true }
    }

    private def onSelectorThread(f: => Unit)(ifClosed: => Unit): Unit = {
      closeLock.synchronized {
        if (closed) {
          ifClosed
        } else {
          pendingThunks.synchronized {
            pendingThunks.+=(() => f)
          }
          selector.wakeup()
          ()
        }
      }
    }

    private def runPendingThunks: Unit = {
      val thunksToRun = pendingThunks.synchronized { pendingThunks.dequeueAll(_ => true) }
      thunksToRun foreach { _() }
    }

    private val selectorThread = Strategy.daemonThreadFactory("fs2-udp-selector").newThread(new Runnable {
      def run = {
        while (!closed && !Thread.currentThread.isInterrupted) {
          runPendingThunks
          selector.select
          val selectedKeys = selector.selectedKeys.iterator
          while (selectedKeys.hasNext) {
            val key = selectedKeys.next
            selectedKeys.remove
            val channel = key.channel.asInstanceOf[DatagramChannel]
            val attachment = key.attachment.asInstanceOf[Attachment]
            try {
              if (key.isValid) {
                if (key.isReadable) {
                  var success = true
                  while (success && attachment.hasReaders) {
                    val reader = attachment.peekReader.get
                    success = read1(key, channel, attachment, reader)
                    if (success) attachment.dequeueReader
                  }
                }
                if (key.isWritable) {
                  var success = true
                  while (success && attachment.hasWriters) {
                    val (p, writer) = attachment.peekWriter.get
                    success = write1(key, channel, attachment, p, writer)
                    if (success) attachment.dequeueWriter
                  }
                }
                key.interestOps(
                  (if (attachment.hasReaders) SelectionKey.OP_READ else 0) |
                  (if (attachment.hasWriters) SelectionKey.OP_WRITE else 0)
                )
              }
            } catch {
              case t: CancelledKeyException => // Ignore; key was closed
            }
          }
        }
        close
        runPendingThunks
      }
    })
    selectorThread.start()

    override def toString = "AsynchronousSocketGroup"
  }
}
