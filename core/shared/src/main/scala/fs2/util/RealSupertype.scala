package fs2.util

/**
 * A `RealSupertype[A,B]` is evidence that `A <: B`.
 * This module provides implicit `RealSupertype[Sub,Super]` only if
 * `Super` is not one of: `Any`, `AnyVal`, `AnyRef`, `Product`, or `Serializable`.
 */
@annotation.implicitNotFound("Dubious upper bound ${Super} inferred for ${Sub}; supply `RealSupertype.allow[${Sub},${Super}]` here explicitly if this is not due to a type error")
sealed trait RealSupertype[-Sub,Super]

private[fs2] trait NothingSubtypesOthers {
  private val _i00 = new RealSupertype[String,String] {}
  implicit def nothingSubtypesOthers[A](implicit A: RealType[A]): RealSupertype[Nothing,A] =
    _i00.asInstanceOf[RealSupertype[Nothing,A]]
}

private[fs2] trait NothingSubtypesItself extends NothingSubtypesOthers {
  private val _i0 = new RealSupertype[String,String] {}
  implicit def nothingIsSubtypeOfItself: RealSupertype[Nothing,Nothing] =
    _i0.asInstanceOf[RealSupertype[Nothing,Nothing]]
}
object RealSupertype extends NothingSubtypesItself {
  private val _i = new RealSupertype[String,String] {}
  implicit def apply[A<:B,B](implicit B: RealType[B]): RealSupertype[A,B] =
    _i.asInstanceOf[RealSupertype[A,B]]
  def allow[A<:B,B]: RealSupertype[A,B] =
    _i.asInstanceOf[RealSupertype[A,B]]
}

trait RealType[T]
private[fs2] trait RealTypeInstance {
  private val _i0 = new RealType[Unit] {}
  implicit def instance[A]: RealType[A] = _i0.asInstanceOf[RealType[A]]
}

object RealType extends RealTypeInstance {
  private val _i = new RealType[Unit] {}
  implicit val any1 = _i.asInstanceOf[RealType[Any]]
  implicit val any2 = _i.asInstanceOf[RealType[Any]]
}
