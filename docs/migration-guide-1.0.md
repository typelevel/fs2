Upgrading from 0.10 to 1.0? This document summarizes the changes and provides tips on migrating. If you're upgrading from 0.9, take a look at the [0.10 migration guide](migration-guide-0.10.md) first.

This release is focused on taking advantage of new features added to cats-effect 1.0 (and cats-effect 0.10). These features include support for cancelable and bracketable effect types and support for timeouts.

Additionally, this release is focused on API simplification. There are a number of method and type renames, aimed at making the API easier to use and more predictable. Type inference is significantly better, resulting in much less need for `covary`. Finally, the `InvariantOps` encoding is gone (with one exception), resulting in operations being defined directly on `Stream` (and accessible via ScalaDoc).

### Timer / Scheduler

The new `cats.effect.Timer` type was introduced in cats-effect 0.10. This type provides much of the same functionality as the `fs2.Scheduler` type with the added functionality of supporting cancelation of sleeps. Hence, `fs2.Scheduler` has been removed and all of the stream-specific methods have been moved to the `fs2.Stream` companion. A `Timer[IO]` instance is available implicitly for both the JVM and Scala.js, meaning there's no need to allocate and shutdown a timer. Timer instances for other effect types can either be defined manually or derived from the `Timer[IO]` instance via `Timer.derive[F]`.

|0.10 API|1.0 API|
|--------|-------|
|`scheduler.effect.sleep[F](duration)`|`Timer[F].sleep(duration)`|
|`scheduler.sleep[F](duration)`|`Stream.sleep[F](duration)`|
|`scheduler.sleep_[F](duration)`|`Stream.sleep_[F](duration)`|
|`scheduler.awakeEvery[F](duration)`|`Stream.awakeEvery[F](duration)`|
|`scheduler.retry(task, delay, nextDelay, maxRetries)`|`Stream.retry(task, delay, nextDelay, maxRetries)`|
|`scheduler.debounce[F](duration).through(source)`|`source.debounce(duration)`|
|`scheduler.delayCancellable(task, duration)`|`Concurrent[F].race(task, Timer[F].sleep(duration))`|
|`scheduler.delay(source, duration)`|`source.delayBy(duratin)`|

### Cancelation

The `cats.effect.Concurrent` type class was introduced in cats-effect 0.10, providing the ability to start a `F[A]` computation as a lightweight thread and then either wait for the result or cancel the computation. This functionality is used throughout `fs2.concurrent` (formerly `fs2.async`) to support cancelation of asynchronous tasks. Consider the use case of dequeuing an element from a queue and timing out if no element has been received after some specified duration. In FS2 0.10, this had to be done with `q.timedDequeue1`, as simply calling `dequeue1` and racing it with a timeout would leave some residual state inside the queue indicating there's a listener for data. FS2 0.10 had a number of similar methods throughout the API -- `timedGet`, `cancellableDequeue1`, etc. With cats-effect's new `Concurrent` support, these APIs are no longer needed, as we can implement cancelation in a composable fashion.

A good example of the simplification here is the `fs2.async.Promise` type (now `cats.effect.concurrent.Deferred`, more on that later). In FS2 1.0, `Promise` has only 2 methods -- `get` and `complete`. Timed gets and cancelable gets can both be implemented in a straightforward way by combining `p.get` with `Concurrent[F].race` or `Concurrent[F].start`.

### Concurrent

The aforementioned `Concurrent` type class is used pervasively throughout the library now. For the most part, everywhere in FS2 0.10 that used `Effect` has been changed to only require a `Concurrent` instance now. The `Concurrent.start` method ensures that its argument is run asynchronously -- e.g., on a thread pool associated with the platform / type class instance. As a result, `ExecutionContext` is no longer used in the FS2 API. In general, custom code that used both an `Effect[F]` and an `ExecutionContext` should be rewritten to use only a `Concurrent[F]`.

An exception to this change is the `fs2-io` module -- places where there's an interface between FS2 and a callback driven API like Java NIO. In such cases, we now require a `ConcurrentEffect` instance -- something that is both an `Effect` and a `Concurrent`.

Another exception appears in the `fs2-io` module -- places where blocking calls are made to Java APIs (e.g., writing to a `java.io.OutputStream`). In such cases, an explicit blocking `ExecutionContext` must be passed. The blocking calls will be executed on the supplied `ExecutionContext` and then shifted back to the main asynchronous execution mechanism of the effect type (via `Timer[F].shift`).

### Concurrent Data Types

Some of the data types from the old `fs2.async` package have moved to `cats.effect.concurrent` -- specifically, `Ref`, `Promise` (now called `Deferred`), and `Semaphore`. As part of moving these data types, their APIs evolved a bit.

#### Ref

|0.10 API|1.0 API|Notes|
|--------|-------|-----|
|`fs2.async.Ref`|`cats.effect.concurrent.Ref`|
|`fs2.async.refOf[F, A](a)`|`cats.effect.concurrent.Ref.of[F, A](a)`|
|`r.setSync(a)`|`r.set(a)`|
|`r.setAsync(a)`|`r.lazySet(a)`|
|`r.modify(f)`|`r.update(f)`|Returns `F[Unit]` instead of `F[Change[A]]`. See below for notes.|
|`r.modify2(f)`|`r.modify(f)`|Returns `F[B]` isntead of `F[(Change[A], B)]`|
|`r.tryModify(f)`|`r.tryUpdate(f)`|Returns `F[Boolean]` instead of `F[Option[Change[A]]]`|
|`r.tryModify2(f)`|`r.tryModify(f)`|Returns `F[Option[B]]` instead of `F[Option[(Change[A], B)]]`|

Note: `modify`'s signature has changed, so if you want to extract the value before or after the change, you can do it explicitly - `modify`'s argument is now `f: A => (A, B)`, so to apply a change `g: A => B` and get the value before you can do `modify(a => (g(a), a)`. To get both values (before and after), you can do e.g. `modify(a => (g(a), (a, g(a)))`.

#### Deferred

|0.10 API|1.0 API|Notes|
|--------|-------|-----|
|`fs2.async.Promise`|`cats.effect.concurrent.Deferred`|
|`fs2.async.promise[F, A]`|`cats.effect.concurrent.Deferred[F, A]`|0.10 constructor took an `Effect[F]` and `ExecutionContext` whereas 1.0 constructor only takes a `Concurrent[F]`|
|`p.cancellableGet`|`p.get`|`Deferred#get` may be canceled using fiber cancelation|
|`p.timedGet(timeout, scheduler)`|`p.get.timeout(duration)`|`timeout` method comes from `Concurrent[F]` type class and requires an implicit `Timer[F]` in scope|

#### Semaphore

|0.10 API|1.0 API|Notes|
|--------|-------|-----|
|`fs2.async.mutable.Semaphore`|`cats.effect.concurrent.Semaphore`|
|`s.decrement`|`s.acquire`|
|`s.decrementBy(n)`|`s.acquireN(n)`|
|`s.tryDecrement`|`s.tryAcquire(n)`|
|`s.tryDecrementBy(n)`|`s.tryAcquireN(n)`|
|`s.increment`|`s.release`|
|`s.increment`|`s.release`|

#### Signal

The `fs2.async.immutable.Signal` type is now `fs2.concurrent.Signal` while `fs2.async.mutable.Signal` is replaced by `fs2.concurrent.SignallingRef`, which extends both `Signal` and `Ref`. Constructing a signalling ref is now accomplished via `SignallingRef[F, A](a)` instead of `fs2.async.signalOf`.

#### Queue

`Queue` also moved from `fs2.async.mutable.Queue` to `fs2.concurrent.Queue`. `Queue` now extends both `Enqueue` and `Dequeue`, allowing you to better delineate whether a function produces or consumes elements. Size information has been moved to `InspectableQueue`, so the runtime cost of maintaining size information isn't paid for all usages. Constructors are on the `Queue` and `InspectableQueue` companions -- e.g., `Queue.bounded(n)` or `Queue.synchronous`.

#### Topic

`Topic` moved from `fs2.async.mutable.Topic` to `fs2.concurrent.Topic` and the constructor has moved to `Topic.apply`.

#### fs2.async Package Object

The `fs2.async` package object contained constructors for concurrent data types and miscellaneous concurrency utilities (e.g., `start`, `fork`, `parallelTraverse`). The data type constructors have all been moved to data type companions with the exception of `hold` and `holdOption` which have been moved to methods on `Stream` (e.g., instead of `async.hold(0, src)`, write `src.hold(0)`).

Most of the miscellaneous concurrency utilities are no longer necessary because they are directly supported by cats. For example, `start` now exists on the `cats.effect.Concurrent` type class and `parTraverse` is available for any `Concurrent[F]`.

One exception is `unsafeRunAsync`, which was removed from fs2 without a direct replacement in cats-effect. To run a computation asynchronously, you can use the following:

```scala
// Given F: ConcurrentEffect[F] & import cats.implicits._, cats.effect.implicits._
fa.start.flatMap(_.join).runAsync(_ => IO.unit).unsafeRunSync
```

The only remaining concurrency utility is `fs2.concurrent.once`, which supports memoization of a task.

### Chunks and Segments

In 0.10, a stream was internally represented by `Segment`s and many advanced APIs allowed direct observation and manipulation of the segments of a stream. In 1.0, a stream is internally represented by `Chunk`s. As a result, all APIs that returned segments now return chunks. For example `s.pull.uncons` returns a `Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]]` now instead of a `Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]]`.

The original promise of `Segment` was better overall stream performance as a consequence of `Segment`'s arbitrary operator fusion. `Segment` delivered on arbitrary operator fusion but through benchmarking, we found overall stream performance was actually worse in most stream use cases. In order for `Segment` to provide arbitrary fusion, algorithms had to be written in a very different way than `Chunk` based algorithms -- e.g., no indexed based access to elements and no direct access to segment size.

By moving back to a chunk based representation of stream, we end up with better performance and a much simpler API.

|0.10 API|1.0 API|Notes|
|--------|-------|-----|
|`s.segments`|`s.chunks`|
|`s.mapSegments`|`s.mapChunks`|
|`s.scanSegments`|`s.scanChunks`|
|`s.scanSegmentsOpt`|`s.scanChunksOpt`|
|`s.pull.unconsChunk`|`s.pull.uncons`|
|`Pull.outputChunk`|`Pull.output`|

### Stream.bracket

The signature of `Stream.bracket` has changed from:

```scala
def bracket[F[_], R, O](acquire: F[R])(use: R => Stream[F, O], release: R => F[Unit]): Stream[F, O]
```

to:

```scala
def bracket[F[_], R](acquire: F[R])(release: R => F[Unit]): Stream[F, R]
```

Note the `use` parameter is no longer passed, as it is redundant with `Stream.bracket(acquire)(release).flatMap(use)`.


### Resources

In 0.10, some APIs returned singleton streams in order to ensure resource finalization occurred. For example, creating a TCP client socket returned `Stream[F, Socket[F]]` -- the stream always emitted a single socket and the the overall stream finalizer freed any resources associated with the socket.

In 1.0, such APIs have been modified to return a `cats.effect.Resource` instead of a singleton stream. For example, creating a tcp client socket now returns a `Resource[F, Socket[F]]`, which can be lifted to a singleton stream via `Stream.resource`.

### Usability based renames

Some methods were renamed to improve discoverability and avoid surprises.

|0.10 API|1.0 API|Notes|
|--------|-------|-----|
|`s.observe1(f)`|`s.evalTap(f)`|`observe1` was too close in name to `observe`, which gave the impression that they had similar performance when in reality, `observe1` was significantly faster|
|`s.join(n)`|`s.parJoin(n)`|`join` conflicted with monadic `join = flatten` method|
|`s.joinUnbounded`|`s.parJoinUnbounded`|

### Interop with scodec-bits

The `fs2-scodec` interop project has been folded directly in to `fs2-core`. The `fs2.interop.scodec.ByteVectorChunk` type is now `fs2.Chunk.ByteVectorChunk`.

### StreamApp

The `StreamApp` class was removed in favor of `cats.effect.IOApp`, which has a much simpler usage model.

```scala
object MyApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    myStream.compile.drain.as(ExitCode.Success)
}
```

### fs2.io Changes

Methods in the `fs2.io` package that performed blocking I/O have been either removed or the blocking has been implemented via a call to `ContextSwitch[F].evalOn(blockingExecutionContext)(...)`. This ensures that a blocking call is not made from the same thread pool used for non-blocking tasks.

For example, `fs2.io.readInputStream` now takes a blocking execution context argument, as well as an implicit `ContextShift[F]` argument. The `readInputStreamAsync` function was removed, as it was redundant with `readInputStream` once the blocking calls were shifted to a dedicated execution context.

Additionally, the `*Async` methods from `fs2.io.file` have been removed (e.g. `readAllAsync`). Those methods used `java.nio.file.AsynchronousFileChannel`, which does *not* perform asynchronous I/O and instead performs blocking I/O on a dedicated thread pool. Hence, these methods are redundant with their blocking equivalents, which shift blocking calls to a dedicated blocking execution context.

### Catenable

The `fs2.Catenable` type has moved to cats-core, was renamed to `cats.data.Chain`, and underwent some minor method name changes (e.g., `snoc` is now `:+`, `singleton` is now `one`).