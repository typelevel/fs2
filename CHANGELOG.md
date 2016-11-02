0.9.2
=====
 - Fixed a bug where traversing a list or vector evaluated effects in reverse order. [#746](https://github.com/functional-streams-for-scala/fs2/issues/746)
 - Fixed `to` and `tov` so that the output of the sink is not drained.
 - Added `groupBy` to `Stream` and `Pipe`.

0.9.1
=====
 - Fixed bug where a stream with a `map` or `flatMap` after an `onError` would result in the error handler not getting run. [#735](https://github.com/functional-streams-for-scala/fs2/issues/735)

0.9.0
=====
 - First release of new design.

