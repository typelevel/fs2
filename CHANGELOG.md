Note: as of 0.10, release notes are available on each release in GitHub - https://github.com/functional-streams-for-scala/fs2/releases

0.9.6
=====
 - Empty deflated streams are now handled properly [#865](https://github.com/functional-streams-for-scala/fs2/pull/865)
 - `concurrent.join` now fails when outer stream terminated [#869](https://github.com/functional-streams-for-scala/fs2/pull/869)

0.9.5
=====
 - Fixed memory leak in `concurrent.join` where up to `maxOpen` streams that had finished executing were kept in memory until the end of the outer stream was reached. [#834](https://github.com/functional-streams-for-scala/fs2/issues/834)
 - Fixed a bug in `fs2.io.JavaInputOutputStream`, ensuring bytes are converted to 0-255 before being returned.
 - Added `pipe2.pause` and `Stream#pause` combinators -- similar to `interrupt` but allows the stream to be resumed.
 - Added `Stream#evalScan` - a variant of `scan` where the supplied function returns an effectful value.
 - Added `pipe.unNone` and `Stream#unNone`, which converts a `Stream[F, Option[A]]` to a `Stream[F, A]`, ignoring any `None` values.

0.9.4
=====
 - Fixed memory leak in `Stream#runFoldScope` introduced by [#808](https://github.com/functional-streams-for-scala/fs2/issues/808).
 - Added OSGi metadata to the core and io JARs.

0.9.3
=====
 - Fixed memory leak in `Signal#discrete`. [#799](https://github.com/functional-streams-for-scala/fs2/issues/799)
 - Significant performance improvements, especially in `map`-heavy streams. [#776](https://github.com/functional-streams-for-scala/fs2/pull/776) [#784](https://github.com/functional-streams-for-scala/fs2/pull/784)
 - Fixed bug in `runFold` which resulted in finalizers being skipped if stream used `uncons` at root without a scope. [#808](https://github.com/functional-streams-for-scala/fs2/pull/808).
 - Improved TCP socket buffer management - read buffer is shared now instead of being allocated on each socket read. [#809](https://github.com/functional-streams-for-scala/fs2/pull/809)
 - Added `>>` to `Stream`.
 - Added `head` to `Stream` and `pipe`.
 - Added `unfoldChunk` and `unfoldChunkEval` to `Stream`.
 - Added `fromAttempt` to `Task`.

0.9.2
=====
 - Fixed a bug where traversing a list or vector evaluated effects in reverse order. [#746](https://github.com/functional-streams-for-scala/fs2/issues/746)
 - Fixed `to` and `tov` so that the output of the sink is not drained. [#754](https://github.com/functional-streams-for-scala/fs2/pull/754)
 - Fixed a bug in `Task.Ref` where a `set` after an `access` `set` did not result in no-op. [#749](https://github.com/functional-streams-for-scala/fs2/pull/749)
 - Added `groupBy` to `Stream` and `Pipe`.

0.9.1
=====
 - Fixed bug where a stream with a `map` or `flatMap` after an `onError` would result in the error handler not getting run. [#735](https://github.com/functional-streams-for-scala/fs2/issues/735)

0.9.0
=====
 - First release of new design.
