Upgrading from 0.9? This document summarizes the changes and provides tips on migrating. If you're upgrading from 0.8 or earlier, take a look at the [0.9 migration guide](migration-guide-0.9.md) first.

### Table of Contents

* [Cats](#cats)
  * [Type Classes](#type-classes)
  * [Task/IO](#taskio)
* [Performance](#performance)
* [API Simplification](#api-simplification)

### Cats

The library now depends on [cats](https://github.com/typelevel/cats) and [cats-effect](https://github.com/typelevel/cats-effect). Scalaz support continues to be provided by the `fs2-scalaz` interop library.

There's some more detail about this change in [#848](https://github.com/functional-streams-for-scala/fs2/issues/848).

#### Type Classes

As a result of this migration, the `fs2.util` package has been removed. The type classes that existed in `fs2.util` have all been replaced by equivalent type classes in cats and cats-effect.

|0.9|1.0|
|---|---|
|`fs2.util.Functor`|`cats.Functor`|
|`fs2.util.Applicative`|`cats.Applicative`|
|`fs2.util.Monad`|`cats.Monad`|
|`fs2.util.Traverse`|`cats.Traverse`|
|`fs2.util.Catchable`|`cats.MonadError[?,Throwable]`|
|`fs2.util.Suspendable`|`cats.effect.Sync`|
|`fs2.util.Async`|`cats.effect.Async`|
|`fs2.util.Effect`|`cats.effect.Effect`|
|`fs2.util.UF1`|`cats.~>`|

Note that cats-effect divides effect functionality up in to type classes a little differently than FS2 0.9. The `cats.effect.Async` type class describes a type constructor that supports the `async` constructor whereas `fs2.util.Async` included concurrency behavior. The type classes in cats-effect purposefully do not provide any means for concurrency. FS2 layers concurrency on top of `cats.effect.Effect` using the same `Ref` based scheme used in 0.9, but defined polymorphically for any `Effect`. This functionality is provided by `fs2.async.ref` and other methods in the `async` package. As a result, most implicit usages of `fs2.util.Async` should be replaced with `cats.effect.Effect` and `scala.concurrent.ExecutionContext`.

#### Task/IO

In addition to the type classes which abstract over effect capture, cats-effect defines a concrete effect type, `cats.effect.IO`.

`IO` was heavily inspired by `fs2.Task` (which in turn was a fork of `scalaz.concurrent.Task`), but includes a number of significant improvements. It's faster than `Task` and has an easier to use API.

As a result `fs2.Task` has been removed. Porting from `Task` to `IO` is relatively straightforward:

|Task|IO|Remarks|
|----|--|-------|
|`Task.delay(a)`|`IO(a)`|Most common operation|
|`Task.now(a)`|`IO.pure(a)`||
|`Task.fail(t)`|`IO.raiseError(t)`||
|`Task(a)`|`IO.shift >> IO(a)`|`shift` takes an implicit `ExecutionContext` and shifts subsequent execution to that context|
|`t.async`|`IO.shift >> t`|Note: same as previous|
|`t.unsafeRun`|`io.unsafeRunSync`|Callable on Scala.js but will throw if an async boundary is encountered|
|`t.unsafeRunAsync(cb)`|`io.unsafeRunAsync(cb)`| |
|`t.unsafeRunFor(limit)`|`io.unsafeRunTimed(limit)`| |
|`t.unsafeRunAsyncFuture`|`io.unsafeToFuture`| |
|`Task.fromFuture(Future { ... })`|`IO.fromFuture(Eval.always(Future { ... }))`| Laziness is explicit via use of `cats.Eval` |
|`Task.async(reg => ...)`|`IO.async(reg => ...)`|Note that `IO.async` does *NOT* thread-shift, unlike `Task.async`. Use `IO.shift` as appropriate (`IO.async` is semantically equivalent to `Task.unforedAsync`)|


### Performance

Performance is significantly better thanks to the introduction of `fs2.Segment`. A `Segment` is a potentially infinite, lazy, pure data structure which supports a variety of fused operations. This is coincidentally similar to the approach taken in [Stream Fusion, to Completeness](https://arxiv.org/pdf/1612.06668v1.pdf), though using a novel approach that does not require code generation.

TODO

### API Simplification

#### Built-in Pipes

 The `fs2.pipe` and `fs2.pipe2` objects have been removed and built-in stream transformations now exist solely as syntax on `Stream`. E.g., `s.through(pipe.take(n))` is now `s.take(n)`. The `fs2.Pipe` and `fs2.Pipe2` objects now exist and contain advanced operations on pipes, like joining a stream of pipes and stepping pipes.

#### Variance Tricks Removed

In 0.9, `Sub1`, `Lub1`, and `RealSupertype` encoded covariance using type-level computations to work around various limitations in type inference. These tricks resulted in confusing type signatures and bad compiler error messages. In 1.0, these type classes have been removed and FS2 uses "regular" covariance.

In 0.9, `Stream(1, 2, 3)` had type `Stream[F,Int]` for all `F`, and Scala would often infer `F` as `Nothing`. This would generally work out fine, as `Stream` is covariant in `F`, but not it doesn't work out all cases due to Scala's special treatment of `Nothing` during type inference. Working around these cases is what led to tricks like `Sub1`.

In 1.0, we avoid these issues by avoiding use of `Nothing` for an effect type -- i.e., the `Stream` constructors are aggressive about returning `Stream[Pure,O]` when there is no effect type. For example, `Stream(1, 2, 3)` now has type `Stream[Pure,Int]`, `Stream.empty` now has type `Stream[Pure,Nothing]`, and so on. This generally works much better:

```scala
val s1: Stream[IO,Int] = Stream(1, 2, 3)
val s2: Stream[IO,Int] = if (guard) Stream.eval(IO(...)) else Stream.empty
val s3: Stream[IO,Int] = s2.flatMap { n => Stream(1, 2, 3) }
val s4: Stream[IO,Int] = Stream(1,2,3).flatMap { n => Stream(1, 2, 3) }
```

There are times when you may have to manually covary a stream -- especially in situations where you had to explicitly supply type parameters in 0.9 (e.g., if in 0.9 you had to write `Stream[IO,Int](1,2,3)`, in 1.0 you *may* have to write `Stream(1,2,3).covary[IO]`).

#### Handle

TODO
