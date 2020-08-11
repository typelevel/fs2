package fs2.internal

import cats.effect.Sync

/** Represents a unique identifier (using object equality). */
final class Token private[fs2] () extends Serializable {
  override def toString: String = s"Token(${hashCode.toHexString})"
}

object Token {

  def apply[F[_]](implicit mk: Mk[F]): F[Token] = mk.newToken

  trait Mk[F[_]] {
    def newToken: F[Token]
  }

  object Mk {
    implicit def instance[F[_]](implicit F: Sync[F]): Mk[F] =
      new Mk[F] {
        override def newToken: F[Token] = F.delay(new Token)
      }
  }
}
