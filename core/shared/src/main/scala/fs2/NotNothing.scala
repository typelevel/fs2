package fs2

/**
 * Witnesses that a type `A` is not the type `Nothing`.
 * 
 * This is used to prevent `flatMap` from being called on a `Stream[F, Nothing]`,
 * which is always incorrect.
 */
sealed trait NotNothing[-A]
object NotNothing {
  @annotation.implicitAmbiguous("Invalid type Nothing found")
  implicit val nothing1: NotNothing[Nothing] = new NotNothing[Nothing] {}
  implicit val nothing2: NotNothing[Nothing] = new NotNothing[Nothing] {}

  implicit def instance[A]: NotNothing[A] = new NotNothing[A] {}
}
