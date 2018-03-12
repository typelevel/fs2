Upgrading from 0.10 to 1.0? This document summarizes the changes and provides tips on migrating. If you're upgrading from 0.9, take a look at the [0.10 migration guide](migration-guide-0.10.md) first.

This release is focused on taking advantage of new features added to cats-effect 1.0 (and cats-effect 0.10). These features include support for cancelable and bracketable effect types and support for timeouts.

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

The `cats.effect.Concurrent` type class was introduced in cats-effect 0.10, providing the ability to start a `F[A]` computation as a lightweight thread and then either wait for the result or cancel the computation. This functionality is used throughout `fs2.async` to support cancelation of asynchronous tasks. Consider the use case of dequeuing an element from a queue and timing out if no element has been received after some specified duration. In FS2 0.10, this had to be done with `q.timedDequeue1`, as simply calling `dequeue1` and racing it with a timeout would leave some residual state inside the queue indicating there's a listener for data. FS2 0.10 had a number of similar methods throughout the API -- `timedGet`, `cancellableDequeue1`, etc. With cats-effect's new `Concurrent` support, these APIs are no longer needed, as we can implement cancelation in a composable fashion.

A good example of the simplification here is the `fs2.async.Promise` type. In FS2 1.0, `Promise` has only 2 methods -- `get` and `complete`. Timed gets and cancelable gets can both be implemented in a straightforward way by combining `p.get` with `Concurrent[F].race` or `Concurrent[F].start`.

### Concurrent

The aforementioned `Concurrent` type class is used pervasively throughout the library now. For the most part, everywhere in FS2 0.10 that used `Effect` has been changed to only require a `Concurrent` instance now. An exception to this change is the `fs2-io` module -- places where there's an interface between FS2 and a callback driven API like Java NIO. In such cases, we now require a `ConcurrentEffect` instance -- something that is both an `Effect` and a `Concurrent`.
