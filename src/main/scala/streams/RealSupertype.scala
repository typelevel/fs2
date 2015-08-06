package streams

/**
 * An implicit `RealSupertype[Sub,Super]` exists only if `Super` is
 * not one of `Product`, `Any`, `AnyRef`, `AnyVal`, or `Serializable`.
 * If it is in this set, the user gets an ambiguous implicit error.
 */
sealed trait RealSupertype[-Sub,+Super]

private[streams] trait RealSupertypeFallback {
  implicit def realSupertype[A]: RealSupertype[A,A] =
    new RealSupertype[A,A] {}
}

/**
 * An implicit `RealType[A]` exists only if `A` is
 * not one of `Product`, `Any`, `AnyRef`, `AnyVal`, or `Serializable`.
 * If it is in this set, the user gets an ambiguous implicit error.
 * If you really want to use one of these types, supply the implicit
 * parameter explicitly.
 */
trait RealType[A]

private[streams] trait RealTypeFallback {
  implicit def realType[A]: RealType[A] = new RealType[A] {}
}

object RealType extends RealTypeFallback {

  implicit val `'Product not a real supertype'` = new RealType[Product] {}
  implicit val `'Scala inferred Product, you have a type error'` = new RealType[Product] {}

  implicit val `'Any not a real supertype'` = new RealType[Any] {}
  implicit val `'Scala inferred Any, you have a type error'` = new RealType[Any] {}

  implicit val `'AnyRef not a real supertype'` = new RealType[AnyRef] {}
  implicit val `'Scala inferred AnyRef, you have a type error'` = new RealType[AnyRef] {}

  implicit val `'AnyVal not a real supertype'` = new RealType[AnyVal] {}
  implicit val `'Scala inferred AnyVal, you have a type error'` = new RealType[AnyVal] {}

  implicit val `'Serializable not a real supertype'` = new RealType[AnyRef] {}
  implicit val `'Scala inferred Serializable, you have a type error'` = new RealType[AnyRef] {}
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
}
