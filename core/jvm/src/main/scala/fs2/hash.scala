package fs2

import java.security.MessageDigest

/** Provides various cryptographic hashes as pipes. */
object hash {

  def md2[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("MD2"))
  def md5[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("MD5"))
  def sha1[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-1"))
  def sha256[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-256"))
  def sha384[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-384"))
  def sha512[F[_]]: Pipe[F,Byte,Byte] = digest(MessageDigest.getInstance("SHA-512"))

  def digest[F[_]](digest: => MessageDigest): Pipe[F,Byte,Byte] = in => Stream.suspend {
    val d = digest
    in.chunks.flatMap { c => d.update(c.toBytes.values); Stream.empty } ++ Stream.chunk(Chunk.bytes(d.digest()))
  }
}
