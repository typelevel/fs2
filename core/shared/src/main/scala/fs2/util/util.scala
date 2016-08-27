package fs2

/** Type classes and other utilities used by [[fs2]]. */
package object util {

  /** Operator alias for `UF1[F,G]`. */
  type ~>[F[_],G[_]] = UF1[F,G]

  /** Alias for `Either[Throwable,A]`. */
  type Attempt[A] = Either[Throwable,A]
}
