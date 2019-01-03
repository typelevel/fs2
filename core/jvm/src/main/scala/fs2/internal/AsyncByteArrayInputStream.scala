package fs2.internal

import scala.collection.mutable
import scala.util.control.NoStackTrace

import java.io.InputStream

/**
  * An in-memory buffered byte InputStream designed to fake continuation suspension by throwing
  * exceptions. This will work so long as any delegating code (such as other InputStreams) perform
  * reads *before* changing any internal state. Reads that are interleaved with state changes may
  * result in invalid continuations.
  */
private[fs2] final class AsyncByteArrayInputStream(val bound: Int) extends InputStream {
  private[this] val bytes = new mutable.ListBuffer[Array[Byte]]
  private[this] var headOffset = 0
  private[this] var _available = 0

  // checkpoint
  private[this] var cbytes: List[Array[Byte]] = _
  private[this] var cheadOffset: Int = _
  private[this] var cavailable: Int = _

  def checkpoint(): Unit = {
    cbytes = bytes.toList // we can do better here, probably
    cheadOffset = headOffset
    cavailable = _available
  }

  def restore(): Unit = {
    bytes.clear()
    val _ = bytes ++= cbytes // we can do a lot better here
    headOffset = cheadOffset
    _available = cavailable
  }

  def release(): Unit =
    cbytes = null

  def push(chunk: Array[Byte]): Boolean =
    if (available < bound) {
      val _ = bytes += chunk
      _available += chunk.length
      true
    } else {
      false
    }

  override def available() = _available

  def read(): Int = {
    val buf = new Array[Byte](1)
    val _ = read(buf)
    buf(0) & 0xff
  }

  override def read(target: Array[Byte], off: Int, len: Int): Int =
    if (bytes.isEmpty) {
      throw AsyncByteArrayInputStream.AsyncError
    } else {
      val head = bytes.head
      val copied = math.min(len, head.length - headOffset)
      System.arraycopy(head, headOffset, target, off, copied)

      _available -= copied

      val _ = headOffset += copied

      if (headOffset >= head.length) {
        headOffset = 0
        val _ = bytes.remove(0)
      }

      copied
    }
}

private[fs2] object AsyncByteArrayInputStream {

  final case object AsyncError extends Error with NoStackTrace
}
