package scalaz.stream.io

import scodec.bits.ByteVector

import java.nio.ByteBuffer

/**
 * The class of data that has an efficient mapping onto arrays of bytes.  Note that the signatures
 * of this typeclass imply mutable state, since it is designed to be used in low-level IO operations.
 * Natural instances of this typeclass are provided for the following types:
 *
 * - `Array[Byte]`
 * - `ByteBuffer`
 * - `ByteVector`
 * - `Seq[Byte]`
 *
 * This is primarily useful in the `io.toInputStream` method.
 */
trait Chunked[-A] {

  def length(a: A): Int

  /**
   * *Mutably* copies the bytes out of `a`, starting at the *logical offset* into `a` indexed
   * by `offset1`, copying into `buffer` starting at `offset2` and proceeding for at most
   * `length` bytes.  The total number of bytes copied is returned, which should be a value
   * between 0 and `length` (inclusive).
   *
   * Note that bounds checks may be assumed (i.e. no caller will ever ask for a `length` of
   * greater than the value of `length(a)`, and `buffer` will always be sized appropriately
   * to receive the maximal set of bytes).  Thus, there are no error conditions on this
   * function.  It is possible to simply refuse to copy any bytes (by returning 0), but note
   * that callers are well within their rights to generate an infinite loop if `copy` returns
   * 0 repeatedly.  Handle chunk-level error signaling through some other mechanism.
   */
  def copy(a: A, offset1: Int, buffer: Array[Byte], offset2: Int, length: Int): Int
}

private[io] trait LowPriorityImplicits {

  // we want to be *absolutely* sure this is the very last thing the compiler looks for
  implicit object ByteSeqChunked extends Chunked[Seq[Byte]] {

    def length(seq: Seq[Byte]): Int = seq.length

    def copy(seq: Seq[Byte], offset1: Int, buffer: Array[Byte], offset2: Int, length: Int): Int = {
      var i = offset2
      seq drop offset1 take length foreach { b =>
        buffer(i) = b
        i += 1
      }
      length
    }
  }
}

object Chunked extends LowPriorityImplicits {

  def apply[A](implicit ev: Chunked[A]): Chunked[A] = ev

  implicit object ByteArrayChunked extends Chunked[Array[Byte]] {

    def length(array: Array[Byte]): Int = array.length

    def copy(array: Array[Byte], offset1: Int, buffer: Array[Byte], offset2: Int, length: Int): Int = {
      System.arraycopy(array, offset1, buffer, offset2, length)
      length
    }
  }

  implicit object ByteBufferChunked extends Chunked[ByteBuffer] {

    def length(bb: ByteBuffer): Int = bb.remaining()

    /**
     * Please note that this method advances the position of the input `ByteBuffer`!  This is an
     * efficiency concession.
     */
    def copy(bb: ByteBuffer, offset1: Int, buffer: Array[Byte], offset2: Int, length: Int): Int = {
      bb.position(offset1)
      bb.get(buffer, offset2, length)
      length
    }
  }

  implicit object ByteVectorChunked extends Chunked[ByteVector] {

    def length(bv: ByteVector): Int = bv.length

    def copy(bv: ByteVector, offset1: Int, buffer: Array[Byte], offset2: Int, length: Int): Int = {
      (bv drop offset1 take length).copyToArray(buffer, offset2)
      length
    }
  }
}