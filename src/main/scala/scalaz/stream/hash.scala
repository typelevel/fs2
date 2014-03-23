package scalaz.stream

import java.security.MessageDigest
import scodec.bits.ByteVector

import Process._
import process1._

object hash {
  /** Computes the MD2 hash of the input elements. */
  val md2: Process1[ByteVector,ByteVector] = messageDigest("MD2")

  /** Computes the MD5 hash of the input elements. */
  val md5: Process1[ByteVector,ByteVector] = messageDigest("MD5")

  /** Computes the SHA-1 hash of the input elements. */
  val sha1: Process1[ByteVector,ByteVector] = messageDigest("SHA-1")

  /** Computes the SHA-256 hash of the input elements. */
  val sha256: Process1[ByteVector,ByteVector] = messageDigest("SHA-256")

  /** Computes the SHA-384 hash of the input elements. */
  val sha384: Process1[ByteVector,ByteVector] = messageDigest("SHA-384")

  /** Computes the SHA-512 hash of the input elements. */
  val sha512: Process1[ByteVector,ByteVector] = messageDigest("SHA-512")

  private def messageDigest(algorithm: String): Process1[ByteVector,ByteVector] = {
    def go(digest: MessageDigest): Process1[ByteVector,ByteVector] =
      await1[ByteVector].flatMap { bytes =>
        digest.update(bytes.toArray)
        go(digest) orElse emitLazy(ByteVector.view(digest.digest()))
      }
    suspend1(go(MessageDigest.getInstance(algorithm)))
  }
}
