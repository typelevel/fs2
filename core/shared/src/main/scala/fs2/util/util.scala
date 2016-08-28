package fs2

/**
 * Type classes and other utilities used by [[fs2]].
 *
 * == Type Classes ==
 *
 * This package includes a minimal set of common functional type classes -- e.g., [[Functor]], [[Applicative]],
 * [[Monad]], [[Traverse]] -- along with some extensions that are more specialized like [[Suspendable]],
 * [[Catchable]], [[Effect]], and [[Async]].
 *
 * Infix syntax is provided for all of these type classes by the [[fs2.util.syntax]] object, which is used by
 * adding `import fs2.util.syntax._`. The infix syntax has no runtime cost. The type classes generally do
 * not define many derived methods (with the exception of `Async`). Instead, derived methods are defined soley infix.
 * For example, `Monad` does not define `flatten` but monad infix syntax does.
 *
 * Note that these type classes are intentionally minimal. Providing a general purpose set of functional
 * structures is not a goal of FS2.
 */
package object util {

  /** Operator alias for `UF1[F,G]`. */
  type ~>[F[_],G[_]] = UF1[F,G]

  /** Alias for `Either[Throwable,A]`. */
  type Attempt[A] = Either[Throwable,A]
}
