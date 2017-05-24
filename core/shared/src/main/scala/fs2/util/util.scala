package fs2

/**
 * Type classes and other utilities used by [[fs2]].
 */
package object util {

  /** Alias for `Either[Throwable,A]`. */
  type Attempt[+A] = Either[Throwable,A]
}
