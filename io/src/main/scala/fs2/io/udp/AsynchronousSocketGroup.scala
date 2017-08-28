package fs2
package io
package udp

import scala.collection.JavaConverters._
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.FiniteDuration

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException,ClosedChannelException,DatagramChannel,InterruptedByTimeoutException,Selector,SelectionKey}
import java.util.ArrayDeque
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch}

/**
 * Supports read/write operations on an arbitrary number of UDP sockets using a shared selector thread.
 *
 * Each `AsynchronousSocketGroup` is assigned a single daemon thread that performs all read/write operations.
 */

sealed trait AsynchronousSocketGroup {
  private[udp] type Context
  private[udp] def register(channel: DatagramChannel): Context
  private[udp] def read(ctx: Context, timeout: Option[FiniteDuration], cb: Either[Throwable,Packet] => Unit): Unit
  private[udp] def write(ctx: Context, packet: Packet, timeout: Option[FiniteDuration], cb: Option[Throwable] => Unit): Unit
  private[udp] def close(ctx: Context): Unit

  /**
   * Shuts down the daemon thread used for selection and rejects all future register/read/write requests.
   */
  def close(): Unit
}

object AsynchronousSocketGroup {
  /*
   * Used to avoid copying between Chunk[Byte] and ByteBuffer during writes within the selector thread,
   * as it can be expensive depending on particular implementation of Chunk.
   */
  private class WriterPacket(val remote: InetSocketAddress, val bytes: ByteBuffer)

  def apply(): AsynchronousSocketGroup = new AsynchronousSocketGroup {

    class Timeout(val expiry: Long, onTimeout: () => Unit) {
      private var done: Boolean = false
      def cancel(): Unit = done = true
      def timedOut(): Unit = {
        if (!done) {
          done = true
          onTimeout()
        }
      }
    }

    object Timeout {
      def apply(duration: FiniteDuration)(onTimeout: => Unit): Timeout = new Timeout(System.currentTimeMillis + duration.toMillis, () => onTimeout)
      implicit val ordTimeout: Ordering[Timeout] = Ordering.by[Timeout, Long](_.expiry)
    }

    private class Attachment(
      readers: ArrayDeque[(Either[Throwable,Packet] => Unit,Option[Timeout])] = new ArrayDeque(),
      writers: ArrayDeque[((WriterPacket,Option[Throwable] => Unit),Option[Timeout])] = new ArrayDeque()
    ) {

      def hasReaders: Boolean = !readers.isEmpty

      def peekReader: Option[Either[Throwable,Packet] => Unit] = {
        if (readers.isEmpty) None
        else Some(readers.peek()._1)
      }

      def dequeueReader: Option[Either[Throwable,Packet] => Unit] = {
        if (readers.isEmpty) None
        else {
          val (reader, timeout) = readers.pop()
          timeout.foreach(_.cancel)
          Some(reader)
        }
      }

      def queueReader(reader: Either[Throwable,Packet] => Unit, timeout: Option[Timeout]): () => Unit = {
        if (closed) {
          reader(Left(new ClosedChannelException))
          timeout.foreach(_.cancel)
          () => ()
        } else {
          val r = (reader, timeout)
          readers.add(r)
          () => { readers.remove(r); () }
        }
      }

      def hasWriters: Boolean = !writers.isEmpty

      def peekWriter: Option[(WriterPacket,Option[Throwable] => Unit)] = {
        if (writers.isEmpty) None
        else Some(writers.peek()._1)
      }

      def dequeueWriter: Option[(WriterPacket,Option[Throwable] => Unit)] = {
        if (writers.isEmpty) None
        else {
          val (w, timeout) = writers.pop()
          timeout.foreach(_.cancel)
          Some(w)
        }
      }

      def queueWriter(writer: (WriterPacket,Option[Throwable] => Unit), timeout: Option[Timeout]): () => Unit = {
        if (closed) {
          writer._2(Some(new ClosedChannelException))
          timeout.foreach(_.cancel)
          () => ()
        } else {
          val w = (writer, timeout)
          writers.add(w)
          () => { writers.remove(w); () }
        }
      }

      def close(): Unit = {
        readers.iterator.asScala.foreach { case (cb, t) =>
          cb(Left(new ClosedChannelException))
          t.foreach(_.cancel)
        }
        readers.clear
        writers.iterator.asScala.foreach { case ((_, cb), t) =>
          cb(Some(new ClosedChannelException))
          t.foreach(_.cancel)
        }
        writers.clear
      }
    }

    type Context = SelectionKey

    private val selector = Selector.open()
    private val closeLock = new Object
    @volatile private var closed = false
    private val pendingThunks: ConcurrentLinkedQueue[() => Unit] = new ConcurrentLinkedQueue()
    private val pendingTimeouts: PriorityQueue[Timeout] = new PriorityQueue()
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

    override def read(key: SelectionKey, timeout: Option[FiniteDuration], cb: Either[Throwable,Packet] => Unit): Unit = {
      onSelectorThread {
        val channel = key.channel.asInstanceOf[DatagramChannel]
        val attachment = key.attachment.asInstanceOf[Attachment]
        var cancelReader: () => Unit = null
        val t = timeout map { t0 => Timeout(t0) {
          cb(Left(new InterruptedByTimeoutException))
          if (cancelReader ne null) cancelReader()
        }}
        if (attachment.hasReaders) {
          cancelReader = attachment.queueReader(cb, t)
          t.foreach { t => pendingTimeouts += t }
        } else {
          if (!read1(key, channel, attachment, cb)) {
            cancelReader = attachment.queueReader(cb, t)
            t.foreach { t => pendingTimeouts += t }
            try { key.interestOps(key.interestOps | SelectionKey.OP_READ); () }
            catch { case t: CancelledKeyException => /* Ignore; key was closed */ }
          }
        }
      } { cb(Left(new ClosedChannelException)) }
    }

    private def read1(key: SelectionKey, channel: DatagramChannel, attachment: Attachment, reader: Either[Throwable,Packet] => Unit): Boolean = {
      try {
        val src = channel.receive(readBuffer).asInstanceOf[InetSocketAddress]
        if (src eq null) {
          false
        } else {
          readBuffer.flip
          val bytes = new Array[Byte](readBuffer.remaining)
          readBuffer.get(bytes)
          readBuffer.clear
          reader(Right(new Packet(src, Chunk.bytes(bytes))))
          true
        }
      } catch {
        case t: IOException => reader(Left(t))
        true
      }
    }

    override def write(key: SelectionKey, packet: Packet, timeout: Option[FiniteDuration], cb: Option[Throwable] => Unit): Unit = {
      val writerPacket = {
        val bytes = {
          val srcBytes = packet.bytes.toBytes
          if (srcBytes.size == srcBytes.values.size) srcBytes.values else {
            val destBytes = new Array[Byte](srcBytes.size)
            Array.copy(srcBytes.values, 0, destBytes, srcBytes.offset, srcBytes.size)
            destBytes
          }
        }
        new WriterPacket(packet.remote, ByteBuffer.wrap(bytes))
      }
      onSelectorThread {
        val channel = key.channel.asInstanceOf[DatagramChannel]
        val attachment = key.attachment.asInstanceOf[Attachment]
        var cancelWriter: () => Unit = null
        val t = timeout map { t0 => Timeout(t0) {
          cb(Some(new InterruptedByTimeoutException))
          if (cancelWriter ne null) cancelWriter()
        }}
        if (attachment.hasWriters) {
          cancelWriter = attachment.queueWriter((writerPacket, cb), t)
          t.foreach { t => pendingTimeouts += t }
        } else {
          if (!write1(key, channel, attachment, writerPacket, cb)) {
            cancelWriter = attachment.queueWriter((writerPacket, cb), t)
            t.foreach { t => pendingTimeouts += t }
            try { key.interestOps(key.interestOps | SelectionKey.OP_WRITE); () }
            catch { case t: CancelledKeyException => /* Ignore; key was closed */ }
          }
        }
      } { cb(Some(new ClosedChannelException)) }
    }

    private def write1(key: SelectionKey, channel: DatagramChannel, attachment: Attachment, packet: WriterPacket, cb: Option[Throwable] => Unit): Boolean = {
      try {
        val sent = channel.send(packet.bytes, packet.remote)
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

    override def close(): Unit = {
      closeLock.synchronized { closed = true }
    }

    private def onSelectorThread(f: => Unit)(ifClosed: => Unit): Unit = {
      closeLock.synchronized {
        if (closed) {
          ifClosed
        } else {
          pendingThunks.add(() => f)
          selector.wakeup()
          ()
        }
      }
    }

    private def runPendingThunks(): Unit = {
      var next = pendingThunks.poll()
      while (next ne null) {
        next()
        next = pendingThunks.poll()
      }
    }

  private val selectorThread: Thread = internal.ThreadFactories.named("fs2-udp-selector", true).newThread(new Runnable {
      def run = {
        while (!closed && !Thread.currentThread.isInterrupted) {
          runPendingThunks
          val timeout = pendingTimeouts.headOption.map { t => (t.expiry - System.currentTimeMillis) max 0L }
          selector.select(timeout.getOrElse(0L))
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
          val now = System.currentTimeMillis
          var nextTimeout = pendingTimeouts.headOption
          while (nextTimeout.isDefined && nextTimeout.get.expiry <= now) {
            nextTimeout.get.timedOut
            pendingTimeouts.dequeue()
            nextTimeout = pendingTimeouts.headOption
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
