package scalaz.stream

import java.security.MessageDigest
import scodec.bits.ByteVector

import Process._
import process1._

/**
 * @define MutableProcess1 [[https://github.com/scalaz/scalaz-stream/blob/master/src/test/scala/scalaz/stream/examples/MutableProcess1.scala `MutableProcess1`]]
 * @define MutableImpl @note This process uses mutable state as an
 *   implementation detail which can become observable under certain
 *   conditions. See $MutableProcess1 for more information.
 */
object hash {
  /**
   * Computes the MD2 hash of the input elements.
   * $MutableImpl
   */
  val md2: Process1[ByteVector,ByteVector] = messageDigest("MD2")

  /**
   * Computes the MD5 hash of the input elements.
   * $MutableImpl
   */
  val md5: Process1[ByteVector,ByteVector] = messageDigest("MD5")

  /**
   * Computes the SHA-1 hash of the input elements.
   * $MutableImpl
   */
  val sha1: Process1[ByteVector,ByteVector] = messageDigest("SHA-1")

  /**
   * Computes the SHA-256 hash of the input elements.
   * $MutableImpl
   */
  val sha256: Process1[ByteVector,ByteVector] = messageDigest("SHA-256")

  /**
   * Computes the SHA-384 hash of the input elements.
   * $MutableImpl
   */
  val sha384: Process1[ByteVector,ByteVector] = messageDigest("SHA-384")

  /**
   * Computes the SHA-512 hash of the input elements.
   * $MutableImpl
   */
  val sha512: Process1[ByteVector,ByteVector] = messageDigest("SHA-512")

  private def messageDigest(algorithm: String): Process1[ByteVector,ByteVector] = {
    def go(digest: MessageDigest): Process1[ByteVector,ByteVector] =
      receive1 { bytes =>
        digest.update(bytes.toArray)
        go(digest)
      }
    suspend {
      val digest = MessageDigest.getInstance(algorithm)
      drainLeading(go(digest) onComplete emit(ByteVector.view(digest.digest())))
    }
  }
}
