package fs2

/**
 * Marker trait. A `Stream[Pure,O]` can be safely cast to a `Stream[Nothing,O]`,
 * but the `Stream[Pure,O]` has better type inference properties in some places.
 * See usage in `[[Stream.pull]]`.
 */
sealed trait Pure[+A]
