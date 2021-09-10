/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package net

import java.net.InetSocketAddress
import java.nio.{Buffer, ByteBuffer}
import java.nio.channels.{CancelledKeyException, DatagramChannel, SelectionKey, Selector}
import java.util.ArrayDeque
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicLong

import com.comcast.ip4s._

import CollectionCompat._
import java.util.concurrent.ThreadFactory

/** Supports read/write operations on an arbitrary number of UDP sockets using a shared selector
  * thread.
  *
  * Each `AsynchronousDatagramSocketGroup` is assigned a single daemon thread that performs all
  * read/write operations.
  */
private[net] trait AsynchronousDatagramSocketGroup {
  type Context
  def register(channel: DatagramChannel): Context
  def read(
      ctx: Context,
      cb: Either[Throwable, Datagram] => Unit
  ): () => Unit
  def write(
      ctx: Context,
      datagram: Datagram,
      cb: Option[Throwable] => Unit
  ): () => Unit
  def close(ctx: Context): Unit
  def close(): Unit
}

private[net] object AsynchronousDatagramSocketGroup {
  /*
   * Used to avoid copying between Chunk[Byte] and ByteBuffer during writes within the selector thread,
   * as it can be expensive depending on particular implementation of Chunk.
   */
  private class WriterDatagram(val remote: InetSocketAddress, val bytes: ByteBuffer)

  def unsafe(threadFactory: ThreadFactory): AsynchronousDatagramSocketGroup =
    new AsynchronousDatagramSocketGroup {
      private class Attachment(
          readers: ArrayDeque[(Long, Either[Throwable, Datagram] => Unit)] = new ArrayDeque(),
          writers: ArrayDeque[(Long, (WriterDatagram, Option[Throwable] => Unit))] =
            new ArrayDeque()
      ) {
        def hasReaders: Boolean = !readers.isEmpty

        def peekReader: Option[Either[Throwable, Datagram] => Unit] =
          if (readers.isEmpty) None
          else Some(readers.peek()._2)

        def dequeueReader: Option[Either[Throwable, Datagram] => Unit] =
          if (readers.isEmpty) None
          else {
            val (_, reader) = readers.pop()
            Some(reader)
          }

        def queueReader(
            id: Long,
            reader: Either[Throwable, Datagram] => Unit
        ): () => Unit =
          if (closed) {
            reader(Left(new ClosedChannelException))
            () => ()
          } else {
            val r = (id, reader)
            readers.add(r)
            () => { readers.remove(r); () }
          }

        def cancelReader(id: Long): Unit = {
          readers.removeIf { case (rid, _) => id == rid }
          ()
        }

        def hasWriters: Boolean = !writers.isEmpty

        def peekWriter: Option[(WriterDatagram, Option[Throwable] => Unit)] =
          if (writers.isEmpty) None
          else Some(writers.peek()._2)

        def dequeueWriter: Option[(WriterDatagram, Option[Throwable] => Unit)] =
          if (writers.isEmpty) None
          else {
            val (_, w) = writers.pop()
            Some(w)
          }

        def queueWriter(
            id: Long,
            writer: (WriterDatagram, Option[Throwable] => Unit)
        ): () => Unit =
          if (closed) {
            writer._2(Some(new ClosedChannelException))
            () => ()
          } else {
            val w = (id, writer)
            writers.add(w)
            () => { writers.remove(w); () }
          }

        def close(): Unit = {
          readers.iterator.asScala.foreach { case (_, cb) =>
            cb(Left(new ClosedChannelException))
          }
          readers.clear
          writers.iterator.asScala.foreach { case (_, (_, cb)) =>
            cb(Some(new ClosedChannelException))
          }
          writers.clear
        }

        def cancelWriter(id: Long): Unit = {
          writers.removeIf { case (rid, _) => id == rid }
          ()
        }
      }

      type Context = SelectionKey

      private val ids = new AtomicLong(Long.MinValue)

      private val selector = Selector.open()
      private val closeLock = new Object
      @volatile private var closed = false
      private val pendingThunks: ConcurrentLinkedQueue[() => Unit] =
        new ConcurrentLinkedQueue()
      private val readBuffer = ByteBuffer.allocate(1 << 16)

      override def register(channel: DatagramChannel): Context = {
        var key: SelectionKey = null
        val latch = new CountDownLatch(1)
        onSelectorThread {
          channel.configureBlocking(false)
          val attachment = new Attachment()
          key = channel.register(selector, 0, attachment)
          latch.countDown
        }(latch.countDown)
        latch.await
        if (key eq null) throw new ClosedChannelException()
        key
      }

      override def read(
          key: SelectionKey,
          cb: Either[Throwable, Datagram] => Unit
      ): () => Unit = {
        val readerId = ids.getAndIncrement()
        val attachment = key.attachment.asInstanceOf[Attachment]

        onSelectorThread {
          val channel = key.channel.asInstanceOf[DatagramChannel]
          var cancelReader: () => Unit = null
          if (attachment.hasReaders) {
            cancelReader = attachment.queueReader(readerId, cb)
          } else if (!read1(channel, cb)) {
            cancelReader = attachment.queueReader(readerId, cb)
            try {
              key.interestOps(key.interestOps | SelectionKey.OP_READ); ()
            } catch {
              case _: CancelledKeyException => /* Ignore; key was closed */
            }
          }
        }(cb(Left(new ClosedChannelException)))

        () => onSelectorThread(attachment.cancelReader(readerId))(())
      }

      private def read1(
          channel: DatagramChannel,
          reader: Either[Throwable, Datagram] => Unit
      ): Boolean =
        try {
          val src = channel.receive(readBuffer).asInstanceOf[InetSocketAddress]
          if (src eq null)
            false
          else {
            val srcAddr = SocketAddress.fromInetSocketAddress(src)
            (readBuffer: Buffer).flip()
            val bytes = new Array[Byte](readBuffer.remaining)
            readBuffer.get(bytes)
            (readBuffer: Buffer).clear()
            reader(Right(Datagram(srcAddr, Chunk.array(bytes))))
            true
          }
        } catch {
          case t: IOException =>
            reader(Left(t))
            true
        }

      override def write(
          key: SelectionKey,
          datagram: Datagram,
          cb: Option[Throwable] => Unit
      ): () => Unit = {
        val writerId = ids.getAndIncrement()
        val writerDatagram = {
          val bytes = {
            val srcBytes = datagram.bytes.toArraySlice
            if (srcBytes.size == srcBytes.values.size) srcBytes.values
            else {
              val destBytes = new Array[Byte](srcBytes.size)
              Array.copy(srcBytes.values, srcBytes.offset, destBytes, 0, srcBytes.size)
              destBytes
            }
          }
          new WriterDatagram(datagram.remote.toInetSocketAddress, ByteBuffer.wrap(bytes))
        }
        val attachment = key.attachment.asInstanceOf[Attachment]
        onSelectorThread {
          val channel = key.channel.asInstanceOf[DatagramChannel]
          var cancelWriter: () => Unit = null
          if (attachment.hasWriters) {
            cancelWriter = attachment.queueWriter(writerId, (writerDatagram, cb))
          } else if (!write1(channel, writerDatagram, cb)) {
            cancelWriter = attachment.queueWriter(writerId, (writerDatagram, cb))
            try {
              key.interestOps(key.interestOps | SelectionKey.OP_WRITE); ()
            } catch {
              case _: CancelledKeyException => /* Ignore; key was closed */
            }
          }
        }(cb(Some(new ClosedChannelException)))

        () => onSelectorThread(attachment.cancelWriter(writerId))(())
      }

      private def write1(
          channel: DatagramChannel,
          datagram: WriterDatagram,
          cb: Option[Throwable] => Unit
      ): Boolean =
        try {
          val sent = channel.send(datagram.bytes, datagram.remote)
          if (sent > 0) {
            cb(None)
            true
          } else
            false
        } catch {
          case e: IOException =>
            cb(Some(e))
            true
        }

      override def close(key: SelectionKey): Unit =
        onSelectorThread {
          val channel = key.channel.asInstanceOf[DatagramChannel]
          val attachment = key.attachment.asInstanceOf[Attachment]
          key.cancel()
          channel.close()
          attachment.close()
        }(())

      override def close(): Unit =
        closeLock.synchronized { closed = true }

      private def onSelectorThread(f: => Unit)(ifClosed: => Unit): Unit =
        closeLock.synchronized {
          if (closed)
            ifClosed
          else {
            pendingThunks.add(() => f)
            selector.wakeup()
            ()
          }
        }

      private def runPendingThunks(): Unit = {
        var next = pendingThunks.poll()
        while (next ne null) {
          next()
          next = pendingThunks.poll()
        }
      }

      private val selectorThread: Thread =
        threadFactory.newThread(new Runnable {
          def run = {
            while (!closed && !Thread.currentThread.isInterrupted) {
              runPendingThunks()
              selector.select(0L)
              val selectedKeys = selector.selectedKeys.iterator
              while (selectedKeys.hasNext) {
                val key = selectedKeys.next
                selectedKeys.remove
                val channel = key.channel.asInstanceOf[DatagramChannel]
                val attachment = key.attachment.asInstanceOf[Attachment]
                try if (key.isValid) {
                  if (key.isReadable) {
                    var success = true
                    while (success && attachment.hasReaders) {
                      val reader = attachment.peekReader.get
                      success = read1(channel, reader)
                      if (success) attachment.dequeueReader
                    }
                  }
                  if (key.isWritable) {
                    var success = true
                    while (success && attachment.hasWriters) {
                      val (p, writer) = attachment.peekWriter.get
                      success = write1(channel, p, writer)
                      if (success) attachment.dequeueWriter
                    }
                  }
                  key.interestOps(
                    (if (attachment.hasReaders) SelectionKey.OP_READ else 0) |
                      (if (attachment.hasWriters) SelectionKey.OP_WRITE else 0)
                  )
                } catch {
                  case _: CancelledKeyException => // Ignore; key was closed
                }
              }
            }
            close()
            runPendingThunks()
          }
        })
      selectorThread.start()

      override def toString = "AsynchronousDatagramSocketGroup"
    }
}
