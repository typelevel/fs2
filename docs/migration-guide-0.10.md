Upgrading from 0.9 to 0.10? This document summarizes the changes and provides tips on migrating. If you're upgrading from 0.8 or earlier, take a look at the [0.9 migration guide](migration-guide-0.9.md) first.

### Cats

The library now depends on [cats](https://github.com/typelevel/cats) and [cats-effect](https://github.com/typelevel/cats-effect). Scalaz support continues to be provided by the `fs2-scalaz` interop library.

There's some more detail about this change in [#848](https://github.com/functional-streams-for-scala/fs2/issues/848).

#### Type Classes

As a result of this migration, the `fs2.util` package has been removed. The type classes that existed in `fs2.util` have all been replaced by equivalent type classes in cats and cats-effect.

|0.9|0.10|
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
|`Task.async(reg => ...)`|`IO.async(reg => ...)`|Note that `IO.async` does *NOT* thread-shift, unlike `Task.async`. Use `IO.shift` as appropriate (`IO.async` is semantically equivalent to `Task.unforkedAsync`)|

### Performance

Performance is significantly better thanks to the introduction of `fs2.Segment`. A `Segment` is a potentially infinite, lazy, pure data structure which supports a variety of fused operations. This is coincidentally similar to the approach taken in [Stream Fusion, to Completeness](https://arxiv.org/pdf/1612.06668v1.pdf), though using a novel approach that does not require code generation.

Instead of a `Stream` being made up of `Chunk`s like in 0.9, it is now made up of `Segment`s. `Chunk[O]` is a subtype of `Segment[O,Unit]`. Many of the operations which operated in terms of chunks now operate in terms of segments. Occassionally, there are operations that are specialized for chunks -- typically when index based access to the underlying elements is more performant than the benefits of operator fusion.

### API Simplification

#### Built-in Pipes

 The `fs2.pipe` and `fs2.pipe2` objects have been removed and built-in stream transformations now exist solely as syntax on `Stream`. E.g., `s.through(pipe.take(n))` is now `s.take(n)`. The `fs2.Pipe` and `fs2.Pipe2` objects now exist and contain advanced operations on pipes, like joining a stream of pipes and stepping pipes. In general, 0.10 removes redundant ways of doing things, preferring a single mechanism when possible.

#### Variance Tricks Removed

In 0.9, `Sub1`, `Lub1`, and `RealSupertype` encoded covariance using type-level computations to work around various limitations in type inference. These tricks resulted in confusing type signatures and bad compiler error messages. In 0.10, these type classes have been removed and FS2 uses "regular" covariance.

In 0.9, `Stream(1, 2, 3)` had type `Stream[F,Int]` for all `F`, and Scala would often infer `F` as `Nothing`. This would generally work out fine, as `Stream` is covariant in `F`, but not it doesn't work out all cases due to Scala's special treatment of `Nothing` during type inference. Working around these cases is what led to tricks like `Sub1`.

In 0.10, we avoid these issues by avoiding use of `Nothing` for an effect type -- i.e., the `Stream` constructors are aggressive about returning `Stream[Pure,O]` when there is no effect type. For example, `Stream(1, 2, 3)` now has type `Stream[Pure,Int]`, `Stream.empty` now has type `Stream[Pure,Nothing]`, and so on. This generally works much better:

```scala
val s1: Stream[IO,Int] = Stream(1, 2, 3)
val s2: Stream[IO,Int] = if (guard) Stream.eval(IO(...)) else Stream.empty
val s3: Stream[IO,Int] = s2.flatMap { n => Stream(1, 2, 3) }
val s4: Stream[IO,Int] = Stream(1,2,3).flatMap { n => Stream(1, 2, 3) }
```

There are times when you may have to manually covary a stream -- especially in situations where you had to explicitly supply type parameters in 0.9 (e.g., if in 0.9 you had to write `Stream[IO,Int](1,2,3)`, in 0.10 you *may* have to write `Stream(1,2,3).covary[IO]`).

#### Handle

In 0.9, the `fs2.Handle[F,O]` type provided an API bridge between streams and pulls. Writing a custom pull involved obtaining a handle for a stream and using a method on the handle to obtain a pull (e.g., `receive`). This often involved boilerplate like `s.open.flatMap { h => h.receive { ... }}.close` or `s.pull { h => h.receive { ... } }`.

In 0.10, the `Handle` type has been removed. Instead, custom pulls are written directly against `Stream` objects. The `pull` method on `Stream` now returns a `Stream.ToPull` object, which has methods for getting a `Pull` from the `Stream`. For example:

```scala
// Equivalent to s.take(1)
s.pull.uncons1.flatMap {
  case None => Pull.pure(())
  case Some((hd, tl)) => Pull.output(hd)
}.stream
```

There are a number of other minor API changes evident in this example, and many that aren't evident:
 - the `await*` methods from `Handle` are now called `uncons*` on `ToPull`
 - `uncons1` returns a `Pull[F,Nothing,Option[(O,Stream[F,O])]]` -- more on this in the next section
 - the `receive*` methods have been removed in favor of `uncons*.flatMap(...)`
 - the `open` method has no analog in this design -- `s.pull` gives a `ToPull` which can then be used to directly obtain a `Pull`
 - the `close` method on `Pull` has been renamed to `stream`
 - methods that used to return `Chunk`s generally return `Segment`s now. There's often a chunk based replacement (e.g., `unconsChunk`) in such scenarios. Try to use the `Segment` variants though, as they often lead to *significantly* better performance.

#### Pull

The `Pull` API has changed a little -- in 0.9, `Pull[F,O,R]` supported the notion of a "done" pull -- a pull which terminated without returning an `R` value. Internally, the pull type was represented by a free monad with a result type of `Option[R]`. This allowed any `Pull[F,O,R]` to terminate early by using `Pull.done`.

In 0.10, this notion has been removed. A `Pull[F,O,R]` always evaluates to an `R` and there's no direct equivalent to `Pull.done`. To signal early termination, many pulls use an `Option` in the resource position -- e.g., `Pull[F,O,Option[R]]`, where a `None` represents termination (e.g., exhaustion of input from the source stream or an upstream pull terminating early). In the example in the last section, we saw that `uncons1` returns a `Pull[F,Nothing,Option[(O,Stream[F,O])]]` -- this tells us that pulling a single element from the stream either results in termination, if the stream is empty, or a single element along with the tail of the stream.

As a result of this change, many combinators have slightly different shapes. Consider `Pull.loop`:

```scala
def loop[F[_],O,R](using: R => Pull[F,O,Option[R]]): R => Pull[F,O,Option[R]] =
  r => using(r) flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }
```

In order for `loop` to know when to stop looping, it needs some indication that `using` is done. In 0.9, this signal was baked in to `Pull` but in 0.10 the returned pull must explicitly signal completion via a `None`.

#### Schedulers and Time

The methods on `fs2.time` have been moved to the `fs2.Scheduler` class. The JVM version of the `Scheduler` object supports automatic allocation and shutdown of the thread pool via the `apply` method. The Scala.js version of the `Scheduler` object still provides a single `Scheduler.default` value.

Example usage on the JVM looks like:

```scala
Scheduler[IO](corePoolSize = 1).flatMap { scheduler =>
  scheduler.awakeEvery[IO](1.second).flatMap(_ => Stream(1, 2, 3))
}
```

`Scheduler` has some new low level operations like `fixedDelay` and `fixedRate` which should replace most uses of `awakeEvery`. `fixedRate` is equivalent to `awakeEvery` but returns `Unit` values in the stream instead of the elapsed duration.

`Scheduler` also has some convenience operations like `scheduler.delay(s, 10.seconds)`.

The `debounce` pipe has moved to the `Scheduler` class also. As a result, FS2 no longer requires passing schedulers implicitly.

The `fs2.time.{ duration, every }` methods have been moved to `Stream` as they have no dependency on scheduling.

#### Merging

The semantics of merging a drained stream with another stream have changed. In 0.9, it was generally safe to do things like:

```scala
Stream.eval(async.signalOf[IO]).flatMap { s =>
  Stream.emit(s) mergeHaltR source.evalMap(s.set).drain
}
```

The general idea behind this pattern is to create a producer and a consumer and then combine them in to a single stream by merging the consumer with the result of draining the producer. In 0.10, the two streams used in a merge are only consulted when downstream needs an element. As a result, it's possible to deadlock when using this pattern. This pattern relies on the fact that the drained producer will continue to execute in parallel with the consumer, *even when the downstream never asks for another element from the merged stream*.

To address this use case, the `concurrently` method has been added. Instead of `consumer.mergeHaltR(producer.drain)`, use `consumer.concurrently(producer)`. See the ScalaDoc for `concurrently` for more details.

Given that most usage of merging a drained stream with another stream should be replaced with `concurrently`, we've removed `mergeDrainL` and `mergeDrainR`.

### Minor API Changes

- The `fs2.concurrent` object has been removed in favor of calling the `join` method on a `Stream` (e.g., `s.join(n)`).
- `Stream.append` has been removed in favor of `s.append(s2)` or `s ++ s2`.
- `fs2.Strategy` has been removed in favor of `scala.concurrent.ExecutionContext`.
- `Sink` now has a companion object with various common patterns for constructing sinks (e.g., `Sink(s => IO(println(s)))`).
- `ScopedFuture` has been renamed to `AsyncPull`.
- There is no `uncons1Async` (or any other equivalent to the old `await1Async`).
- The `pull2` method on `Stream` no longer exists (unncessary due to lack of `Handle`). Replace by calling `.pull` on either `Stream`.
- `NonEmptyChunk` no longer exists (and empty `Chunks` *can* be emitted).
- The `Attempt` alias no longer exists - replace with `Either[Throwable,A]`.

#### Cats Type Class Instances

Note that both `Stream` and `Pull` have type class instances for `cats.effect.Sync`, and hence all super type classes (e.g., `Monad`). These instances are defined in the `Stream` and `Pull` companion objects but they are *NOT* marked implicit. To use them implicitly, they must be manually assigned to an implicit val. This is because the Cats supplied syntax conflicts with `Stream` and `Pull` syntax, resulting in methods which ignore the covariance of `Stream` and `Pull`. Considering this is almost never the right option, these instances are non-implicit.
