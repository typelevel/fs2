package fs2
package io
package udp

import scala.collection.mutable.{Queue=>MutableQueue}

import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, Selector, SelectionKey, ClosedChannelException}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

sealed trait AsynchronousSocketGroup {
  private[udp] def register(channel: DatagramChannel): (AsynchronousSocketGroup.Attachment, SelectionKey)
  private[udp] def enqueue(f: => Unit): Unit
}

object AsynchronousSocketGroup {

  private[udp] class Attachment(
    readers: MutableQueue[Either[Throwable,Packet] => Unit] = MutableQueue(),
    writers: MutableQueue[(Packet,Option[Throwable] => Unit)] = MutableQueue()
  ) {

    def dequeueReader: Option[Either[Throwable,Packet] => Unit] = {
      if (readers.isEmpty) None
      else Some(readers.dequeue())
    }

    def queueReader(reader: Either[Throwable,Packet] => Unit): Unit = {
      readers += reader
      ()
    }

    def hasReaders: Boolean = readers.nonEmpty

    def peekWriter: Option[(Packet,Option[Throwable] => Unit)] = {
      if (writers.isEmpty) None
      else Some(writers.head)
    }

    def dequeueWriter: Option[(Packet,Option[Throwable] => Unit)] = {
      if (writers.isEmpty) None
      else Some(writers.dequeue())
    }

    def queueWriter(writer: (Packet,Option[Throwable] => Unit)): Unit = {
      writers += writer
      ()
    }

    def hasWriters: Boolean = writers.nonEmpty

    def close: Unit = {
      readers.foreach { cb => cb(Left(new ClosedChannelException)) }
      readers.clear
      writers.foreach { case (_, cb) => cb(Some(new ClosedChannelException)) }
      writers.clear
    }
  }

  def apply(): AsynchronousSocketGroup = new AsynchronousSocketGroup {

    private val selectorLock = new ReentrantLock()
    private val selector = Selector.open()
    private val pendingThunks: MutableQueue[() => Unit] = MutableQueue()

    override def register(channel: DatagramChannel): (AsynchronousSocketGroup.Attachment, SelectionKey) = {
      channel.configureBlocking(false)
      val attachment = new AsynchronousSocketGroup.Attachment()
      val key = {
        selectorLock.lock
        try {
          selector.wakeup
          channel.register(selector, 0, attachment)
        } finally {
          selectorLock.unlock
        }
      }
      (attachment, key)
    }

    override def enqueue(f: => Unit): Unit = {
      pendingThunks.synchronized {
        pendingThunks.+=(() => f)
      }
      selector.wakeup()
      ()
    }

    private val doneNow = new AtomicBoolean(false)
    private val selectorThread = new Thread(new Runnable {
      def run = try {
        val readBuffer = ByteBuffer.allocate(1 << 16)
        while (!doneNow.get) {
          pendingThunks.synchronized { while (pendingThunks.nonEmpty) pendingThunks.dequeue()() }
          selectorLock.lock
          selectorLock.unlock
          selector.select()
          val selectedKeys = selector.selectedKeys.iterator
          while (selectedKeys.hasNext) {
            val key = selectedKeys.next
            selectedKeys.remove
            val channel = key.channel.asInstanceOf[DatagramChannel]
            val attachment = key.attachment.asInstanceOf[Attachment]
            if (key.isValid) {
              if (key.isReadable) {
                readBuffer.clear
                val src = channel.receive(readBuffer)
                if (src ne null) {
                  attachment.dequeueReader match {
                    case Some(reader) =>
                      readBuffer.flip
                      val bytes = Array.ofDim[Byte](readBuffer.remaining)
                      readBuffer.get(bytes)
                      readBuffer.clear
                      reader(Right(new Packet(src, Chunk.bytes(bytes))))
                      if (!attachment.hasReaders) key.interestOps(key.interestOps & ~SelectionKey.OP_READ)
                    case None =>
                      sys.error("key marked for read but no reader")
                  }
                }
              }
              if (key.isWritable) {
                attachment.peekWriter match {
                  case Some((p, cb)) =>
                    val sent = channel.send(ByteBuffer.wrap(p.bytes.toArray), p.remote)
                    if (sent > 0) {
                      attachment.dequeueWriter
                      cb(None)
                      if (!attachment.hasWriters) key.interestOps(key.interestOps & ~SelectionKey.OP_WRITE)
                    }
                  case None =>
                    sys.error("key marked for write but no writer")
                }
              }
            }
          }
        }
      } catch { case t: Throwable => t.printStackTrace }
    })
    selectorThread.setName("fs2-udp-selector")
    selectorThread.setDaemon(true)
    selectorThread.start()
  }
}

