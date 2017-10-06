package fs2

import java.security.MessageDigest

/** Provides various cryptographic hashes as pipes. */
object hash {

  /** Computes an MD2 digest. */
  def md2[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("MD2"))

  /** Computes an MD5 digest. */
  def md5[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("MD5"))

  /** Computes a SHA-1 digest. */
  def sha1[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-1"))

  /** Computes a SHA-256 digest. */
  def sha256[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-256"))

  /** Computes a SHA-384 digest. */
  def sha384[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-384"))

  /** Computes a SHA-512 digest. */
  def sha512[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-512"))

  /**
   * Computes the digest of the the source stream, emitting the digest as a chunk
   * after completion of the source stream.
   */
  def digest[F[_]](digest: => MessageDigest): Pipe[F,Byte,Byte] =
    in => Stream.suspend {
      in.chunks.
        fold(digest) { (d, c) =>
          val bytes = c.toBytes
          d.update(bytes.values, bytes.offset, bytes.size)
          d
        }.flatMap { d => Stream.chunk(Chunk.bytes(d.digest())) }
    }
}
