package scalaz.stream

import java.security.MessageDigest
import scodec.bits.ByteVector

import Process._
import process1._

import scalaz.{Tag, @@}

/**
 * @define MutableProcess1 [[https://github.com/scalaz/scalaz-stream/blob/master/src/test/scala/scalaz/stream/examples/MutableProcess1.scala `MutableProcess1`]]
 * @define MutableImpl @note This process uses mutable state as an
 *   implementation detail which can become observable under certain
 *   conditions. See $MutableProcess1 for more information.
 */
object hash {
  /** responsible for providing a MessageDigest for a type */
  trait Digestable[A] {
    def newDigester: MessageDigest
  }

  object Digestable {
    def apply[A: Digestable] = implicitly[Digestable[A]]

    private[hash] def named[A](algorithm: String): Digestable[A] =
      new Digestable[A] {
        def newDigester =
          MessageDigest.getInstance(algorithm)
      }
  }

  sealed trait MD2
  implicit val MD2Digestable = Digestable.named[MD2]("MD2")

  sealed trait MD5
  implicit val MD5Digestable = Digestable.named[MD5]("MD5")

  sealed trait SHA1
  implicit val SHA1Digestable = Digestable.named[SHA1]("SHA-1")

  sealed trait SHA256
  implicit val SHA256Digestable = Digestable.named[SHA256]("SHA-256")

  sealed trait SHA384
  implicit val SHA384Digestable = Digestable.named[SHA384]("SHA-384")

  sealed trait SHA512
  implicit val SHA512Digestable = Digestable.named[SHA512]("SHA-512")

  /**
   * Computes the MD2 hash of the input elements.
   * $MutableImpl
   */
  val md2: Process1[ByteVector,ByteVector @@ MD2] = messageDigest[MD2]

  /**
   * Computes the MD5 hash of the input elements.
   * $MutableImpl
   */
  val md5: Process1[ByteVector,ByteVector @@ MD5] = messageDigest[MD5]

  /**
   * Computes the SHA-1 hash of the input elements.
   * $MutableImpl
   */
  val sha1: Process1[ByteVector,ByteVector @@ SHA1] = messageDigest[SHA1]

  /**
   * Computes the SHA-256 hash of the input elements.
   * $MutableImpl
   */
  val sha256: Process1[ByteVector,ByteVector @@ SHA256] = messageDigest[SHA256]

  /**
   * Computes the SHA-384 hash of the input elements.
   * $MutableImpl
   */
  val sha384: Process1[ByteVector,ByteVector @@ SHA384] = messageDigest[SHA384]

  /**
   * Computes the SHA-512 hash of the input elements.
   * $MutableImpl
   */
  val sha512: Process1[ByteVector,ByteVector @@ SHA512] = messageDigest[SHA512]

  def messageDigest[A: Digestable]: Process1[ByteVector,ByteVector @@ A] = {
    def go(digest: MessageDigest): Process1[ByteVector,ByteVector @@ A] =
      receive1 { bytes =>
        digest.update(bytes.toByteBuffer)
        go(digest)
      }
    suspend {
      val digest = Digestable[A].newDigester
      drainLeading(go(digest) onComplete emit( Tag[ByteVector,A](ByteVector.view(digest.digest())) ))
    }
  }
}
