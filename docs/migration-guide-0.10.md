Upgrading from 0.9 to 0.10? This document summarizes the changes and provides tips on migrating. If you're upgrading from 0.8 or earlier, take a look at the [0.9 migration guide](migration-guide-0.9.md) first.

### Cats

The library now depends on [cats](https://github.com/typelevel/cats) and [cats-effect](https://github.com/typelevel/cats-effect). Scalaz support continues to be provided by the `fs2-scalaz` interop library.

There's some more detail about this change in [#848](https://github.com/functional-streams-for-scala/fs2/issues/848).

#### Type Classes

As a result of this migration, the `fs2.util` package is significantly smaller. The type classes that existed in `fs2.util` have all been replaced by equivalent type classes in cats and cats-effect.

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

`fs2.util.UF1` still exists because it is contravariant in its first type parameter and covariant in its second, while `cats.~>` is invariant in both parameters. In FS2 0.11, `UF1` will be removed.

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

### Minor API Changes

- The `fs2.concurrent` object is deprecated in favor of calling the `join` method on `Stream` (e.g., `Stream(...).join(n)`).
- `fs2.Strategy` has been removed in favor of `scala.concurrent.ExecutionContext`.
