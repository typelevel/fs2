package fs2

/**
 * Indicator that a stream evaluates no effects.
 *
 * A `Stream[Pure,O]` can be safely cast to a `Stream[Nothing,O]`,
 * but `Stream[Pure,O]` has better type inference properties in some places.
 * See usage in `[[Stream.pull]]`.
 */
sealed trait Pure[+A]
