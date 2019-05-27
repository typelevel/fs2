package fs2.internal

/** This object is a special singleton-instance Throwable that we use as a place-holder,
  * to indicate that we do not have, in fact, any exception; but without using any
  * `Left`, `Right`, or `Some` objects
  *
  */
private[fs2] final case object NoExceptionIsAGoodException extends Throwable
