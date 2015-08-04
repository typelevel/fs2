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
  // idea for naming due to @mpilquist

  implicit def `'Product not a real supertype'`[A] = new RealSupertype[A,Product] {}
  implicit def `'Scala inferred Product, you have a type error'`[A] = new RealSupertype[A,Product] {}

  implicit def `'Any not a real supertype'`[A] = new RealSupertype[A,Any] {}
  implicit def `'Scala inferred Any, you have a type error'`[A] = new RealSupertype[A,Any] {}

  implicit def `'AnyRef not a real supertype'`[A] = new RealSupertype[A,AnyRef] {}
  implicit def `'Scala inferred AnyRef, you have a type error'`[A] = new RealSupertype[A,AnyRef] {}

  implicit def `'AnyVal not a real supertype'`[A] = new RealSupertype[A,AnyVal] {}
  implicit def `'Scala inferred AnyVal, you have a type error'`[A] = new RealSupertype[A,AnyVal] {}

  implicit def `'Serializable not a real supertype'`[A] = new RealSupertype[A,AnyRef] {}
  implicit def `'Scala inferred Serializable, you have a type error'`[A] = new RealSupertype[A,AnyRef] {}

  implicit def realSupertypeRefl[A]: RealSupertype[A,A] =
    new RealSupertype[A,A] {}
}
