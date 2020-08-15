package fs2

/**
  * Witnesses that a type `A` is not the type `Nothing`.
  *
  * This is used to prevent operations like `flatMap` from being called
  * on a `Stream[F, Nothing]`. Doing so is typically incorrect as the
  * function passed to `flatMap` of shape `Nothing => Stream[F, B]` can
  * never be called.
  */
sealed trait NotNothing[-A]
object NotNothing {
  @annotation.implicitAmbiguous("Invalid type Nothing found in prohibited position")
  implicit val nothing1: NotNothing[Nothing] = new NotNothing[Nothing] {}
  implicit val nothing2: NotNothing[Nothing] = new NotNothing[Nothing] {}

  implicit def instance[A]: NotNothing[A] = new NotNothing[A] {}
}
