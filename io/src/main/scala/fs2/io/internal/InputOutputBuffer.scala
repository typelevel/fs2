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
import java.util.concurrent.Semaphore

/** Thread safe circular byte buffer which connects a [[java.io.OutputStream]]
  * to a [[java.io.OutputStream]] in a memory efficient manner, without copying
  * bytes unnecessarily.
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
private[io] final class InputOutputBuffer(private[this] val capacity: Int) { self =>

  private[this] val buffer: Array[Byte] = new Array(capacity)

  private[this] var head: Int = 0
  private[this] var tail: Int = 0

  private[this] var closed: Boolean = false

  private[this] val readerPermit: Semaphore = new Semaphore(1)
  private[this] val writerPermit: Semaphore = new Semaphore(1)

  val inputStream: InputStream = new InputStream {
    def read(): Int = {
      readerPermit.acquire()

      while (true) {
        self.synchronized {
          if (head != tail) {
            val byte = buffer(head % capacity) & 0xff
            head += 1
            writerPermit.release()
            readerPermit.release()
            return byte
          } else if (closed) {
            readerPermit.release()
            return -1
          }
        }

        readerPermit.acquire()
      }

      -1
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (b eq null) throw new NullPointerException("Cannot read into a null byte array")
      else if (off < 0)
        throw new IndexOutOfBoundsException(s"Negative offset into the byte array: $off")
      else if (len < 0) throw new IndexOutOfBoundsException(s"Negative read length specified: $len")
      else if (len > b.length - off)
        throw new IndexOutOfBoundsException(
          s"Specified length is greater than the remaining length of the byte array after the offset: len = $len, capacity = ${b.length - off}"
        )

      readerPermit.acquire()

      var offset = off
      var length = len

      var success = false
      var res = 0
      var cont = true

      while (cont) {
        self.synchronized {
          if (head != tail) {
            val available = tail - head
            val toRead = math.min(available, length)
            System.arraycopy(buffer, head % capacity, b, offset, toRead)
            head += toRead
            offset += toRead
            length -= toRead
            res += toRead
            success = true
            writerPermit.release()
            if (length == 0) {
              readerPermit.release()
              cont = false
            }
          } else if (closed) {
            readerPermit.release()
            cont = false
          }
        }

        if (cont) {
          readerPermit.acquire()
        }
      }

      if (success) res else -1
    }

    override def close(): Unit = self.synchronized {
      closed = true
      readerPermit.release()
      writerPermit.release()
    }

    override def available(): Int = self.synchronized {
      if (closed) 0 else tail - head
    }
  }

  val outputStream: OutputStream = new OutputStream {
    def write(b: Int): Unit = {
      while (true)
        self.synchronized {
          if (tail - head < capacity) {
            buffer(tail % capacity) = (b & 0xff).toByte
            tail += 1
            readerPermit.release()
            writerPermit.release()
            return
          } else if (closed) {
            writerPermit.release()
            return
          }
        }

      writerPermit.acquire()
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (b eq null) throw new NullPointerException("Cannot read into a null byte array")
      else if (off < 0)
        throw new IndexOutOfBoundsException(s"Negative offset into the byte array: $off")
      else if (len < 0)
        throw new IndexOutOfBoundsException(s"Negative write length specified: $len")
      else if (len > b.length - off)
        throw new IndexOutOfBoundsException(
          s"Specified length is greater than the remaining length of the byte array after the offset: len = $len, capacity = ${b.length - off}"
        )

      writerPermit.acquire()

      var offset = off
      var length = len

      while (true) {
        self.synchronized {
          if (tail - head < capacity) {
            val available = capacity - (tail - head)
            val toWrite = math.min(available, length)
            System.arraycopy(b, offset, buffer, tail % capacity, toWrite)
            tail += toWrite
            offset += toWrite
            length -= toWrite
            readerPermit.release()
            if (length == 0) {
              return
            }
          } else if (closed) {
            return
          }
        }

        writerPermit.acquire()
      }
    }

    override def close(): Unit = self.synchronized {
      closed = true
      readerPermit.release()
    }
  }
}
