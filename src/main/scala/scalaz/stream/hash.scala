package scalaz.stream

import java.security.MessageDigest

import Process._
import process1._

object hash {
  /** Computes the MD2 hash of the input elements. */
  val md2: Process1[Bytes,Bytes] = messageDigest("MD2")

  /** Computes the MD5 hash of the input elements. */
  val md5: Process1[Bytes,Bytes] = messageDigest("MD5")

  /** Computes the SHA-1 hash of the input elements. */
  val sha1: Process1[Bytes,Bytes] = messageDigest("SHA-1")

  /** Computes the SHA-256 hash of the input elements. */
  val sha256: Process1[Bytes,Bytes] = messageDigest("SHA-256")

  /** Computes the SHA-384 hash of the input elements. */
  val sha384: Process1[Bytes,Bytes] = messageDigest("SHA-384")

  /** Computes the SHA-512 hash of the input elements. */
  val sha512: Process1[Bytes,Bytes] = messageDigest("SHA-512")

  private def messageDigest(algorithm: String): Process1[Bytes,Bytes] = {
    def go(digest: MessageDigest): Process1[Bytes,Bytes] =
      await1[Bytes].flatMap { bytes =>
        bytes.asByteBuffers.foreach(digest.update)
        go(digest) orElse emitLazy(Bytes.unsafe(digest.digest()))
      }
    suspend1(go(MessageDigest.getInstance(algorithm)))
  }
}
