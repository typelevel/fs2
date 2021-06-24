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

package fs2.io.internal

import java.io.{InputStream, OutputStream}
import java.io.InterruptedIOException

/** Thread safe circular byte buffer which pipes a [[java.io.OutputStream]]
  * through a [[java.io.InputStream]] in a memory efficient manner, without
  * copying bytes unnecessarily.
  *
  * @note As per the interfaces of the [[java.io]] classes, all of the
  * operations are blocking in nature and extra care should be taken when using
  * the exposed input/output streams. Thread safety is ensured by
  * synchronizing on individual objects of this class.
  *
  * This is, in spirit, a clean room reimplementation of the
  * [[java.io.PipedInputStream]] and [[java.io.PipedOutputStream]] pair of
  * classes which can be used to achieve similar functionality, without the
  * thread bookkeeping which is confusing in a multi threaded environment like
  * the effect systems in which this code runs.
  *
  * @param capacity the capacity of the allocated circular buffer
  */
private[io] final class PipedStreamBuffer(private[this] val capacity: Int) { self =>

  private[this] val buffer: Array[Byte] = new Array(capacity)

  private[this] var head: Int = 0
  private[this] var tail: Int = 0

  private[this] var closed: Boolean = false

  private[this] val readerPermit: Synchronizer = new Synchronizer()
  private[this] val writerPermit: Synchronizer = new Synchronizer()

  private[this] def cheat(thunk: => Unit): Unit =
    try thunk
    catch {
      case _: InterruptedException =>
        throw new InterruptedIOException()
    }

  val inputStream: InputStream = new InputStream {
    def read(): Int = {
      // Obtain permission to read from the buffer. Used for backpressuring
      // readers when the buffer is empty.
      cheat(readerPermit.acquire())

      while (true) {
        self.synchronized {
          if (head != tail) {
            // There is at least one byte to read.
            val byte = buffer(head % capacity) & 0xff
            // The byte is marked as read by advancing the head of the
            // circular buffer.
            head += 1
            // Notify a writer that some space has been freed up in the buffer.
            writerPermit.release()
            // Notify a next reader.
            readerPermit.release()
            return byte
          } else if (closed) {
            // The Input/OutputStream pipe has been closed. Release the obtained
            // permit such that future readers are not blocked forever.
            readerPermit.release()
            return -1
          }
        }

        // There is nothing to be read from the buffer at this moment.
        // Wait until notified by a writer.
        cheat(readerPermit.acquire())
      }

      // Unreachable code.
      -1
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      // This branching satisfies the InputStream#read interface.
      if (b eq null) throw new NullPointerException("Cannot read into a null byte array")
      else if (off < 0)
        throw new IndexOutOfBoundsException(s"Negative offset into the byte array: $off")
      else if (len < 0) throw new IndexOutOfBoundsException(s"Negative read length specified: $len")
      else if (len > b.length - off)
        throw new IndexOutOfBoundsException(
          s"Specified length is greater than the remaining length of the byte array after the offset: len = $len, capacity = ${b.length - off}"
        )

      // Obtain permission to read from the buffer. Used for backpressuring
      // readers when the buffer is empty.
      cheat(readerPermit.acquire())

      // Variables used to track the progress of the reading. It can happen that
      // the current contents of the buffer cannot fulfill the read request and
      // it needs to be done in several iterations after more data has been
      // written into the buffer.
      var offset = off
      var length = len

      // This method needs to return the number of read bytes, or -1 if the read
      // was unsuccessful.
      var success = false
      var res = 0
      var cont = true

      while (cont) {
        self.synchronized {
          if (head != tail) {
            // There is at least one byte available for reading.
            val available = tail - head
            // Check whether the whole read request can be fulfilled right now,
            // or just a part of it.
            val toRead = math.min(available, length)
            // Transfer the bytes to the provided byte array.
            System.arraycopy(buffer, head % capacity, b, offset, toRead)
            // The bytes are marked as read by advancing the head of the
            // circular buffer.
            head += toRead
            // Read request bookkeeping.
            offset += toRead
            length -= toRead
            res += toRead
            success = true
            // Notify a writer that some space has been freed up in the buffer.
            writerPermit.release()
            if (length == 0) {
              // Notify a next reader.
              readerPermit.release()
              cont = false
            }
          } else if (closed) {
            // The Input/OutputStream pipe has been closed. Release the obtained
            // permit such that future writers are not blocked forever.
            readerPermit.release()
            cont = false
          }
        }

        // We need to be careful not to block the thread if the pipe has been
        // closed, otherwise we risk a deadlock. When the pipe is closed, this
        // reader will loop again and execute the correct logic.
        if (!closed && cont) {
          // There is nothing to be read from the buffer at this moment.
          // Wait until notified by a writer.
          cheat(readerPermit.acquire())
        }
      }

      if (success) res else -1
    }

    override def close(): Unit = self.synchronized {
      if (!closed) {
        closed = true
        // Immediately notify the first registered reader/writer. The rest will
        // be notified by the read/write mechanism which takes into account the
        // state of the Input/OutputStream.
        readerPermit.release()
        writerPermit.release()
      }
    }

    override def available(): Int = self.synchronized {
      if (closed) 0 else tail - head
    }
  }

  val outputStream: OutputStream = new OutputStream {
    def write(b: Int): Unit = {
      // Obtain permission to write to the buffer. Used for backpressuring
      // writers when the buffer is full.
      cheat(writerPermit.acquire())

      while (true) {
        self.synchronized {
          if (tail - head < capacity) {
            // There is capacity for at least one byte to be written.
            buffer(tail % capacity) = (b & 0xff).toByte
            // The byte is marked as written by advancing the tail of the
            // circular buffer.
            tail += 1
            // Notify a reader that there is new data in the buffer.
            readerPermit.release()
            // Notify a next writer.
            writerPermit.release()
            return
          } else if (closed) {
            // The Input/OutputStream pipe has been closed. Release the obtained
            // permit such that future writers are not blocked forever.
            writerPermit.release()
            return
          }
        }

        // The buffer is currently full. Wait until notified by a reader.
        cheat(writerPermit.acquire())
      }
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      // This branching satisfies the OutputStream#write interface.
      if (b eq null) throw new NullPointerException("Cannot read into a null byte array")
      else if (off < 0)
        throw new IndexOutOfBoundsException(s"Negative offset into the byte array: $off")
      else if (len < 0)
        throw new IndexOutOfBoundsException(s"Negative write length specified: $len")
      else if (len > b.length - off)
        throw new IndexOutOfBoundsException(
          s"Specified length is greater than the remaining length of the byte array after the offset: len = $len, capacity = ${b.length - off}"
        )

      // Obtain permission to write to the buffer. Used for backpressuring
      // writers when the buffer is full.
      cheat(writerPermit.acquire())

      // Variables used to track the progress of the writing. It can happen that
      // the current leftover capacity of the buffer cannot fulfill the write
      // request and it needs to be done in several iterations after more data
      // has been written into the buffer.
      var offset = off
      var length = len

      while (true) {
        self.synchronized {
          if (tail - head < capacity) {
            // There is capacity for at least one byte to be written.
            val available = capacity - (tail - head)
            // Check whether the whole write request can be fulfilled right now,
            // or just a part of it.
            val toWrite = math.min(available, length)
            // Transfer the bytes to the provided byte array.
            System.arraycopy(b, offset, buffer, tail % capacity, toWrite)
            // The bytes are marked as written by advancing the tail of the
            // circular buffer.
            tail += toWrite
            // Write request bookkeeping.
            offset += toWrite
            length -= toWrite
            // Notify a reader that there is new data in the buffer.
            readerPermit.release()
            if (length == 0) {
              // Notify a next writer.
              writerPermit.release()
              return
            }
          } else if (closed) {
            // The Input/OutputStream pipe has been closed. Release the obtained
            // permit such that future writers are not blocked forever.
            writerPermit.release()
            return
          }
        }

        // The buffer is currently full. Wait until notified by a reader.
        // We need to be careful not to block the thread if the pipe has been
        // closed, otherwise we risk a deadlock. When the pipe is closed, this
        // writer will loop again and execute the correct logic.
        if (!closed) {
          cheat(writerPermit.acquire())
        }
      }
    }

    override def close(): Unit = self.synchronized {
      if (!closed) {
        closed = true
        // Immediately notify the first registered reader/writer. The rest will
        // be notified by the read/write mechanism which takes into account the
        // state of the Input/OutputStream.
        writerPermit.release()
        readerPermit.release()
      }
    }
  }
}
