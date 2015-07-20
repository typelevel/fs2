package streams

/**
 * An implicit `RealSupertype[Sub,Super]` exists only if `Super` is
 * not one of `Product`, `Any`, `AnyRef`, `AnyVal`, or `Serializable`.
 * If it is in this set, the user gets an ambiguous implicit error.
 */
sealed trait RealSupertype[-Sub,+Super]

private[streams] trait RealSupertypeFallback {
  implicit def realSupertype[A, B >: A]: RealSupertype[A,B] = new RealSupertype[A,B] {}
}

object RealSupertype extends RealSupertypeFallback {
  implicit def `Product not a real supertype`[A] =
    new RealSupertype[A,Product] {}
  implicit def `Product not a real supertype 2`[A] =
    new RealSupertype[A,Product] {}
  implicit def `Any not a real supertype`[A] =
    new RealSupertype[A,Any] {}
  implicit def `Any not a real supertype 2`[A] =
    new RealSupertype[A,Any] {}
  implicit def `AnyRef not a real supertype`[A] =
    new RealSupertype[A,AnyRef] {}
  implicit def `AnyRef not a real supertype 2`[A] =
    new RealSupertype[A,AnyRef] {}
  implicit def `AnyVal not a real supertype`[A] =
    new RealSupertype[A,AnyRef] {}
  implicit def `AnyVal not a real supertype 2`[A] =
    new RealSupertype[A,AnyRef] {}
  implicit def `Serializable not a real supertype`[A] =
    new RealSupertype[A,AnyRef] {}
  implicit def `Serializable not a real supertype 2`[A] =
    new RealSupertype[A,AnyRef] {}

  implicit def realSupertypeRefl[A]: RealSupertype[A,A] =
    new RealSupertype[A,A] {}
}
