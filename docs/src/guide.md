<!--
This markdown file contains code examples which can be compiled using tut. Switch to `project docs`, then do `tut`. Output is produced in `docs/`.
-->

# FS2: The Official Guide

This is the official FS2 guide. It gives an overview of the library and its features and it's kept up to date with the code. If you spot a problem with this guide, a nonworking example, or simply have some suggested improvements, open a pull request! It's very much a WIP.

### Table of contents

* [Overview](#overview)
* [Building streams](#building-streams)
* [Chunking](#chunking)
* [Basic stream operations](#basic-stream-operations)
* [Error handling](#error-handling)
* [Resource acquisition](#resource-acquisition)
* [Exercises (stream building)](#exercises)
* [Statefully transforming streams](#statefully-transforming-streams)
* [Exercises (stream transforming)](#exercises-1)
* [Concurrency](#concurrency)
* [Exercises (concurrency)](#exercises-2)
* [Talking to the external world](#talking-to-the-external-world)
* [Learning more](#learning-more)
* [Appendix: Sane subtyping with better error messages](#a1)
* [Appendix: How interruption of streams works](#a2)

_Unless otherwise noted, the type `Stream` mentioned in this document refers to the type `fs2.Stream` and NOT `scala.collection.immutable.Stream`._

### Overview

The FS2 library has two major capabilites:

* The ability to _build_ arbitrarily complex streams, possibly with embedded effects.
* The ability to _transform_ one or more streams using a small but powerful set of operations

We'll consider each of these in this guide.

### Building streams

A `Stream[F,O]` (formerly `Process`) represents a discrete stream of `O` values which may request evaluation of `F` effects. We'll call `F` the _effect type_ and `O` the _output type_. Let's look at some examples:

```tut
import fs2.Stream

val s0 = Stream.empty
val s1 = Stream.emit(1)
val s1a = Stream(1,2,3) // variadic
val s1b = Stream.emits(List(1,2,3)) // accepts any Seq
```

The `s1` stream has the type `Stream[Nothing,Int]`. It's output type is of course `Int`, and its effect type is `Nothing`, which means it does not require evaluation of any effects to produce its output. Streams that don't use any effects are sometimes called _pure_ streams. You can convert a pure stream to a `List` or `Vector` using:

```tut
s1.toList
s1.toVector
```

Streams have lots of handy 'list-like' functions. Here's a very small sample:

```tut
(Stream(1,2,3) ++ Stream(4,5)).toList
Stream(1,2,3).map(_ + 1).toList
Stream(1,2,3).filter(_ % 2 != 0).toList
Stream(1,2,3).fold(0)(_ + _).toList
Stream(None,Some(2),Some(3)).collect { case Some(i) => i }.toList
Stream.range(0,5).intersperse(42).toList
Stream(1,2,3).flatMap(i => Stream(i,i)).toList
Stream(1,2,3).repeat.take(9).toList
```

Of these, only `flatMap` and `++` are primitive, the rest are built using combinations of various other primitives. We'll take a look at how that works shortly.

So far, we've just looked at pure streams. FS2 streams can also include evaluation of effects:

```tut:book
import fs2.Task

val eff = Stream.eval(Task.delay { println("TASK BEING RUN!!"); 1 + 1 })
```

[`Task`](../core/shared/src/main/scala/fs2/Task.scala) is an effect type we'll see a lot in these examples. Creating a `Task` has no side effects, and `Stream.eval` doesn't do anything at the time of creation, it's just a description of what needs to happen when the stream is eventually interpreted. Notice the type of `eff` is now `Stream[Task,Int]`.

The `eval` function works for any effect type, not just `Task`. FS2 does not care what effect type you use for your streams. You may use the included [`Task` type][Task] for effects or bring your own, just by implementing a few interfaces for your effect type ([`Catchable`][Catchable] and optionally [`Effect`][Effect] or [`Async`][Async] if you wish to use various concurrent operations discussed later). Here's the signature of `eval`:

```Scala
def eval[F[_],A](f: F[A]): Stream[F,A]
```

[Task]: ../core/shared/src/main/scala/fs2/Task.scala
[Catchable]: ../core/shared/src/main/scala/fs2/util/Catchable.scala
[Effect]: ../core/shared/src/main/scala/fs2/util/Effect.scala
[Async]: ../core/shared/src/main/scala/fs2/util/Async.scala

`eval` produces a stream that evaluates the given effect, then emits the result (notice that `F` is unconstrained). Any `Stream` formed using `eval` is called 'effectful' and can't be run using `toList` or `toVector`. If we try we'll get a compile error:

```tut:fail
eff.toList
```

Here's a complete example of running an effectful stream. We'll explain this in a minute:

```tut
eff.runLog.unsafeRun()
```

The first `.runLog` is one of several methods available to 'run' (or perhaps 'compile') the stream to a single effect:

```tut:book
val eff = Stream.eval(Task.delay { println("TASK BEING RUN!!"); 1 + 1 })

val ra = eff.runLog // gather all output into a Vector
val rb = eff.run // purely for effects
val rc = eff.runFold(0)(_ + _) // run and accumulate some result
```

Notice these all return a `Task` of some sort, but this process of compilation doesn't actually _perform_ any of the effects (nothing gets printed).

If we want to run these for their effects 'at the end of the universe', we can use one of the `unsafe*` methods on `Task` (if you are bringing your own effect type, how you run your effects may of course differ):

```tut
ra.unsafeRun()
rb.unsafeRun()
rc.unsafeRun()
rc.unsafeRun()
```

Here we finally see the tasks being executed. As is shown with `rc`, rerunning a task executes the entire computation again; nothing is cached for you automatically.

_Note:_ The various `run*` functions aren't specialized to `Task` and work for any `F[_]` with an implicit `Catchable[F]`---FS2 needs to know how to catch errors that occur during evaluation of `F` effects.

### Chunking

FS2 streams are chunked internally for performance. You can construct an individual stream chunk using `Stream.chunk`, which accepts an `fs2.Chunk` and lots of functions in the library are chunk-aware and/or try to preserve 'chunkiness' when possible:

```tut
import fs2.Chunk

val s1c = Stream.chunk(Chunk.doubles(Array(1.0, 2.0, 3.0)))

s1c.mapChunks {
  case ds : Chunk.Doubles => /* do things unboxed */ ds
  case ds => ds.map(_ + 1)
}
```

_Note:_ The `mapChunks` function is another library primitive. It's used to implement `map` and `filter`.

### Basic stream operations

Streams have a small but powerful set of operations, some of which we've seen already. The key operations are `++`, `map`, `flatMap`, `onError`, and `bracket`:

```tut
val appendEx1 = Stream(1,2,3) ++ Stream.emit(42)
val appendEx2 = Stream(1,2,3) ++ Stream.eval(Task.now(4))

appendEx1.toVector
appendEx2.runLog.unsafeRun()

appendEx1.map(_ + 1).toList
```

The `flatMap` operation is the same idea as lists - it maps, then concatenates:

```tut
appendEx1.flatMap(i => Stream.emits(List(i,i))).toList
```

Regardless of how a `Stream` is built up, each operation takes constant time. So `s ++ s2` takes constant time, regardless of whether `s` is `Stream.emit(1)` or it's a huge stream with millions of elements and lots of embedded effects. Likewise with `s.flatMap(f)` and `onError`, which we'll see in a minute. The runtime of these operations do not depend on the structure of `s`.

### Error handling

A stream can raise errors, either explicitly, using `Stream.fail`, or implicitly via an exception in pure code or inside an effect passed to `eval`:

```tut
val err = Stream.fail(new Exception("oh noes!"))
val err2 = Stream(1,2,3) ++ (throw new Exception("!@#$"))
val err3 = Stream.eval(Task.delay(throw new Exception("error in effect!!!")))
```

All these fail when running:

```tut
try err.toList catch { case e: Exception => println(e) }
```

```tut
try err2.toList catch { case e: Exception => println(e) }
```

```tut
try err3.run.unsafeRun() catch { case e: Exception => println(e) }
```

The `onError` method lets us catch any of these errors:

```tut
err.onError { e => Stream.emit(e.getMessage) }.toList
```

_Note: Don't use `onError` for doing resource cleanup; use `bracket` as discussed in the next section. Also see [this section of the appendix](#a2) for more details._

### Resource acquisition

If you have to acquire a resource and want to guarantee that some cleanup action is run if the resource is acquired, use the `bracket` function:

```tut
val count = new java.util.concurrent.atomic.AtomicLong(0)
val acquire = Task.delay { println("incremented: " + count.incrementAndGet); () }
val release = Task.delay { println("decremented: " + count.decrementAndGet); () }
```

```tut:fail
Stream.bracket(acquire)(_ => Stream(1,2,3) ++ err, _ => release).run.unsafeRun()
```

The inner stream fails, but notice the `release` action is still run:

```tut
count.get
```

No matter how you transform an FS2 `Stream` or where any errors occur, the library guarantees that if the resource is acquired via a `bracket`, the release action associated with that `bracket` will be run. Here's the signature of `bracket`:

```Scala
def bracket[F[_],R,O](acquire: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,O]
```

FS2 guarantees _once and only once_ semantics for resource cleanup actions introduced by the `Stream.bracket` function.

### Exercises

Implement `repeat`, which repeats a stream indefinitely, `drain`, which strips all output from a stream, `eval_`, which runs an effect and ignores its output, and `attempt`, which catches any errors produced by a stream:

```tut
Stream(1,0).repeat.take(6).toList
Stream(1,2,3).drain.toList
Stream.eval_(Task.delay(println("!!"))).runLog.unsafeRun()
(Stream(1,2) ++ (throw new Exception("nooo!!!"))).attempt.toList
```

### Statefully transforming streams

We often wish to statefully transform one or more streams in some way, possibly evaluating effects as we do so. As a running example, consider taking just the first 5 elements of a `s: Stream[Task,Int]`. To produce a `Stream[Task,Int]` which takes just the first 5 elements of `s`, we need to repeatedly await (or pull) values from `s`, keeping track of the number of values seen so far and stopping as soon as we hit 5 elements. In more complex scenarios, we may want to evaluate additional effects as we pull from one or more streams.

Regardless of how complex the job, the `fs2.Pull` and `fs2.Handle` types can usually express it. `Handle[F,I]` represents a 'currently open' `Stream[F,I]`. We obtain one using `Stream.open`, or the method on `Stream`, `s.open`, which returns the `Handle` inside an effect type called `Pull`:

```Scala
// in fs2.Stream object
def open[F[_],I](s: Stream[F,I]): Pull[F,Nothing,Handle[F,I]]
```

The `trait Pull[+F[_],+O,+R]` represents a program that may pull values from one or more `Handle` values, write _output_ of type `O`, and return a _result_ of type `R`. It forms a monad in `R` and comes equipped with lots of other useful operations. See the [`Pull` class](../core/shared/src/main/scala/fs2/Pull.scala) for the full set of operations on `Pull`.

Let's look at the core operation for implementing `take`. It's just a recursive function:

```tut:book
object Pull_ {
  import fs2._

  def take[F[_],O](n: Int)(h: Handle[F,O]): Pull[F,O,Nothing] =
    for {
      (chunk, h) <- if (n <= 0) Pull.done else h.awaitLimit(n)
      tl <- Pull.output(chunk) >> take(n - chunk.size)(h)
    } yield tl
}

Stream(1,2,3,4).pure.pull(Pull_.take(2)).toList
```

Let's break it down line by line:

```Scala
(chunk, h) <- if (n <= 0) Pull.done else Pull.awaitLimit(n)(h)
```

There's a lot going on in this one line:

* If `n <= 0`, we're done, and stop pulling.
* Otherwise we have more values to `take`, so we `h.awaitLimit(n)`, which returns a `(Chunk[A],Handle[F,I])` (again, inside of the `Pull` effect).
* The `h.awaitLimit(n)` reads from the handle but gives us a `Chunk[O]` with _no more than_ `n` elements. (We can also `h.await1` to read just a single element, `h.await` to read a single `Chunk` of however many are available, `h.awaitN(n)` to obtain a `List[Chunk[A]]` totaling exactly `n` elements, and even `h.awaitAsync` and various other _asynchronous_ awaiting functions which we'll discuss in the [Concurrency](#concurrency) section.)
* Using the pattern `(chunk, h)`, we destructure this step to its `chunk: Chunk[O]` and its `h: Handle[F,O]`. This shadows the outer `h`, which is fine here since it isn't relevant anymore. (Note: nothing stops us from keeping the old `h` around and awaiting from it again if we like, though this isn't usually what we want since it will repeat all the effects of that await.)

Moving on, the `Pull.output(chunk)` writes the chunk we just read to the _output_ of the `Pull`. This binds the `O` type in our `Pull[F,O,R]` we are constructing:

```Scala
// in fs2.Pull object
def output[O](c: Chunk[O]): Pull[Nothing,O,Unit]
```

It returns a result of `Unit`, which we generally don't care about. The `p >> p2` operator is equivalent to `p flatMap { _ => p2 }`; it just runs `p` for its effects but ignores its result.

So this line is writing the chunk we read, ignoring the `Unit` result, then recursively calling `take` with the new `Handle`, `h`:

```Scala
      ...
      tl <- Pull.output(chunk) >> take(n - chunk.size)(h)
    } yield tl
```

For the recursive call, we update the state, subtracting the `chunk.size` elements we've seen. Easy!

To actually use a `Pull` to transform a `Stream`, we have to `close` it:

```tut
val s2 = Stream(1,2,3,4).pure.pull(Pull_.take(2))
s2.toList
val s3 = Stream.pure(1,2,3,4).pull(Pull_.take(2)) // alternately
s3.toList
```

_Note:_ The `.pure` converts a `Stream[Nothing,A]` to a `Stream[Pure,A]`. Scala will not infer `Nothing` for a type parameter, so using `Pure` as the effect provides better type inference in some cases.

The `pull` method on `Stream` just calls `open` then `close`. We could express the above as:

```tut
Stream(1,2,3,4).pure.open.flatMap { _.take(2) }.close
```

FS2 takes care to guarantee that any resources allocated by the `Pull` are released when the `close` completes. Note again that _nothing happens_ when we call `.close` on a `Pull`, it is merely establishing a scope in which all resource allocations are tracked so that they may be appropriately freed.

There are lots of useful transformation functions in [`pipe`](../core/shared/src/main/scala/fs2/pipe.scala) and [`pipe2`](../core/shared/src/main/scala/fs2/pipe2.scala) built using the `Pull` type, for example:

```tut:book
import fs2.{pipe, pipe2}

val s = Stream.pure(1,2,3,4,5) // alternately Stream(...).pure

// all equivalent
pipe.take(2)(s).toList
s.through(pipe.take(2)).toList
s.take(2).toList

val ns = Stream.range(10,100,by=10)

// all equivalent
s.through2(ns)(pipe2.zip).toList
pipe2.zip(s, ns).toList
s.zip(ns).toList
```

### Exercises

Try implementing `takeWhile`, `intersperse`, and `scan`:

```tut
Stream.range(0,100).takeWhile(_ < 7).toList
Stream("Alice","Bob","Carol").intersperse("|").toList
Stream.range(1,10).scan(0)(_ + _).toList // running sum
```

### Concurrency

FS2 comes with lots of concurrent operations. The `merge` function runs two streams concurrently, combining their outputs. It halts when both inputs have halted:

```tut:fail
Stream(1,2,3).merge(Stream.eval(Task.delay { Thread.sleep(200); 4 })).runLog.unsafeRun()
```

Oop, we need an `fs2.Strategy` in implicit scope in order to get an `Async[Task]`. Let's add that:

```tut
implicit val S = fs2.Strategy.fromFixedDaemonPool(8, threadName = "worker")

Stream(1,2,3).merge(Stream.eval(Task.delay { Thread.sleep(200); 4 })).runLog.unsafeRun()
```

The `merge` function is defined in [`pipe2`](../core/shared/src/main/scala/fs2/pipe2.scala), along with other useful concurrency functions, like `interrupt` (halts if the left branch produces `false`), `either` (like `merge` but returns an `Either`), `mergeHaltBoth` (halts if either branch halts), and others.

The function `concurrent.join` runs multiple streams concurrently. The signature is:

```Scala
// note Async[F] bound
def join[F[_]:Async,O](maxOpen: Int)(outer: Stream[F,Stream[F,O]]): Stream[F,O]
```

It flattens the nested stream, letting up to `maxOpen` inner streams run at a time. `s merge s2` could be implemented as `concurrent.join(2)(Stream(s,s2))`.

The `Async` bound on `F` is required anywhere concurrency is used in the library. As mentioned earlier, though FS2 provides the [`fs2.Task`][Task] type for convenience, and `Task` has an `Async`, users can bring their own effect types provided they also supply an `Async` instance.

If you examine the implementations of the above functions, you'll see a few primitive functions used. Let's look at those. First, `h.awaitAsync` requests the next step of a `Handle h` asynchronously. Its signature is:

```Scala
type AsyncStep[F[_],A] = ScopedFuture[F, Pull[F, Nothing, (Chunk[A], Handle[F,A])]]

def awaitAsync[F2[_],A2>:A](implicit S: Sub1[F,F2], F2: Async[F2], A2: RealSupertype[A,A2]): Pull[F2, Nothing, Handle.AsyncStep[F2,A2]]
```

A `ScopedFuture[F,A]` represents a running computation that will eventually yield an `A`. A `ScopedFuture[F,A]` has a method `.pull`, of type `Pull[F,Nothing,A]` that can be used to block until the result is available. A `ScopedFuture[F,A]` may be raced with another `ScopedFuture` also---see the implementation of [`pipe2.merge`](../core/shared/src/main/scala/fs2/pipe2.scala).

In addition, there are a number of other concurrency primitives---asynchronous queues, signals, and semaphores. See the [`async` package object](../core/shared/src/main/scala/fs2/async/async.scala) for more details. We'll make use of some of these in the next section when discussing how to talk to the external world.

### Exercises

Without looking at the implementations, try implementing `pipe2.interrupt` and `pipe2.mergeHaltBoth`:

```Scala
type Pipe2[F[_],-I,-I2,+O] = (Stream[F,I], Stream[F,I2]) => Stream[F,O]

/** Like `merge`, but halts as soon as _either_ branch halts. */
def mergeHaltBoth[F[_]:Async,O]: Pipe2[F,O,O,O] = (s1, s2) => ???

/**
 * Let through the `s2` branch as long as the `s1` branch is `false`,
 * listening asynchronously for the left branch to become `true`.
 * This halts as soon as either branch halts.
 */
def interrupt[F[_]:Async,I]: Pipe2[F,Boolean,I,I] = (s1, s2) => ???
```

### Talking to the external world

When talking to the external world, there are a few different situations you might encounter:

* [Functions which execute side effects _synchronously_](#synchronous-effects). These are the easiest to deal with.
* [Functions which execute effects _asynchronously_, and invoke a callback _once_](#asynchronous-effects-callbacks-invoked-once) when completed. Example: fetching 4MB from a file on disk might be a function that accepts a callback to be invoked when the bytes are available.
* [Functions which execute effects asynchronously, and invoke a callback _one or more times_](#asynchronous-effects-callbacks-invoked-multiple-times) as results become available. Example: a database API which asynchronously streams results of a query as they become available.

We'll consider each of these in turn.

#### Synchronous effects

These are easy to deal with. Just wrap these effects in a `Stream.eval`:

```tut:book
def destroyUniverse(): Unit = { println("BOOOOM!!!"); } // stub implementation

val s = Stream.eval_(Task.delay { destroyUniverse() }) ++ Stream("...moving on")
s.runLog.unsafeRun()
```

The way you bring synchronous effects into your effect type may differ. [`Async.delay`](../core/shared/src/main/scala/fs2/util/Async.scala) can be used for this generally, without committing to a particular effect:

```tut:book
import fs2.util.Async

val T = implicitly[Async[Task]]
val s = Stream.eval_(T.delay { destroyUniverse() }) ++ Stream("...moving on")
s.runLog.unsafeRun()
```

When using this approach, be sure the expression you pass to delay doesn't throw exceptions.

#### Asynchronous effects (callbacks invoked once)

Very often, you'll be dealing with an API like this:

```tut:book
trait Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit

  // or perhaps
  def readBytesE(onComplete: Either[Throwable,Array[Byte]] => Unit): Unit =
    readBytes(bs => onComplete(Right(bs)), e => onComplete(Left(e)))

  override def toString = "<connection>"
}
```

That is, we provide a `Connection` with two callbacks (or a single callback that accepts an `Either`), and at some point later, the callback will be invoked _once_. The `Async` trait provides a handy function in these situations:

```Scala
trait Async[F[_]] {
  ...
  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => F[Unit]): F[A]
}
```

Here's a complete example:

```tut:book
val c = new Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit = {
    Thread.sleep(200)
    onSuccess(Array(0,1,2))
  }
}

// recall T: Async[Task]
val bytes = T.async[Array[Byte]] { (cb: Either[Throwable,Array[Byte]] => Unit) =>
  T.delay { c.readBytesE(cb) }
}

Stream.eval(bytes).map(_.toList).runLog.unsafeRun()
```

Be sure to check out the [`fs2.io`](../io) package which has nice FS2 bindings to Java NIO libraries, using exactly this approach.

#### Asynchronous effects (callbacks invoked multiple times)

The nice thing about callback-y APIs that invoke their callbacks once is that throttling/back-pressure can be handled within FS2 itself. If you don't want more values, just don't read them, and they won't be produced! But sometimes you'll be dealing with a callback-y API which invokes callbacks you provide it _more than once_. Perhaps it's a streaming API of some sort and it invokes your callback whenever new data is available. In these cases, you can use an asynchronous queue to broker between the nice stream processing world of FS2 and the external API, and use whatever ad hoc mechanism that API provides for throttling of the producer.

_Note:_ Some of these APIs don't provide any means of throttling the producer, in which case you either have accept possibly unbounded memory usage (if the producer and consumer operate at very different rates), or use blocking concurrency primitives like `fs2.async.boundedQueue` or the the primitives in `java.util.concurrent`.

Let's look at a complete example:

```tut:book
import fs2.async

type Row = List[String]

trait CSVHandle {
  def withRows(cb: Either[Throwable,Row] => Unit): Unit
}

def rows[F[_]](h: CSVHandle)(implicit F: Async[F]): Stream[F,Row] =
  for {
    q <- Stream.eval(async.unboundedQueue[F,Either[Throwable,Row]])
    _ <- Stream.suspend { h.withRows { e => F.unsafeRunAsync(q.enqueue1(e))(_ => ()) }; Stream.emit(()) }
    row <- q.dequeue through pipe.rethrow
  } yield row
```

See [`Queue`](../core/shared/src/main/scala/fs2/async/mutable/Queue.scala) for more useful methods. All asynchronous queues in FS2 track their size, which is handy for implementing size-based throttling of the producer.

### Learning more

Want to learn more?

* Worked examples: these present a nontrivial example of use of the library, possibly making use of lots of different library features.
  * [The README example](ReadmeExample.md)
  * More contributions welcome! Open a PR, following the style of one of the examples above. You can either start with a large block of code and break it down line by line, or work up to something more complicated using some smaller bits of code first.
* Detailed coverage of different modules in the library:
  * File I/O
  * TCP networking
  * UDP networking
  * Contributions welcome! If you are familiar with one of the modules of the library and would like to contribute a more detailed guide for it, submit a PR.

Also feel free to come discuss and ask/answer questions in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and/or on StackOverflow using [the tag FS2](http://stackoverflow.com/tags/fs2).

### <a id="a1"></a> Appendix A1: Sane subtyping with better error messages

`Stream[F,O]` and `Pull[F,O,R]` are covariant in `F`, `O`, and `R`. This is important for usability and convenience, but covariance can often paper over what should really be type errors. Luckily, FS2 implements a trick to catch these situations. For instance:

```tut:fail
Stream.emit(1) ++ Stream.emit("hello")
```

Informative! If you really want a dubious supertype like `Any`, `AnyRef`, `AnyVal`, `Product`, or `Serializable` to be inferred, just follow the instructions in the error message to supply a `RealSupertype` instance explicitly.

```tut
import fs2.util.{Lub1,RealSupertype}

Stream.emit(1).++(Stream("hi"))(RealSupertype.allow[Int,Any], Lub1.id[Nothing])
```

Ugly, as it should be.

### <a id="a2"></a> Appendix A2: How interruption of streams works

In FS2, a stream can terminate in one of three ways:

1. Normal input exhaustion. For instance, the stream `Stream(1,2,3)` terminates after the single chunk (containing the values `1, 2, 3`) is emitted.
2. An uncaught exception. For instance, the stream `Stream(1,2,3) ++ (throw Err)` terminates with `Err` after the single chunk is emitted.
3. Interruption by the stream consumer. Interruption can be _synchronous_, as in `(Stream(1) ++ (throw Err)) take 1`, which will deterministically halt the stream before the `++`, or it can be _asynchronous_, as in `s1 merge s2 take 3`.

Regarding 3:

* A stream will never be interrupted while it is acquiring a resource (via `bracket`) or while it is releasing a resource. The `bracket` function guarantees that if FS2 starts acquiring the resource, the corresponding release action will be run.
* Other than that, Streams can be interrupted in between any two 'steps' of the stream. The steps themselves are atomic from the perspective of FS2. `Stream.eval(eff)` is a single step, `Stream.emit(1)` is a single step, `Stream(1,2,3)` is a single step (emitting a chunk), and all other operations (like `onError`, `++`, and `flatMap`) are multiple steps and can be interrupted. But importantly, user-provided effects that are passed to `eval` are never interrupted once they are started (and FS2 does not have enough knowledge of user-provided effects to know how to interrupt them anyway).
* _Always use `bracket` or a `bracket`-based function like `onFinalize` for supplying resource cleanup logic or any other logic you want to be run regardless of how the stream terminates. Don't use `onError` or `++` for this purpose._

Let's look at some examples of how this plays out, starting with the synchronous interruption case:

```tut
case object Err extends Throwable

(Stream(1) ++ (throw Err)).take(1).toList
(Stream(1) ++ Stream.fail(Err)).take(1).toList
```

The `take 1` uses `Pull` but doesn't examine the entire stream, and neither of these examples will ever throw an error. This makes sense. A bit more subtle is that this code will _also_ never throw an error:

```tut
(Stream(1) onComplete Stream.fail(Err)).take(1).toList
```

The reason is simple: the consumer (the `take(1)`) terminates as soon as it has an element. Once it has that element, it is done consuming the stream and doesn't bother running any further steps of it, so the stream never actually completes normally---it has been interrupted before that can occur. We may be able to see in this case that nothing follows the emitted `1`, but FS2 doesn't know this until it actually runs another step of the stream.

If instead we use `onFinalize`, the code is guaranteed to run, regardless of whether `take` interrupts:

```tut:book
Stream(1).covary[Task].
          onFinalize(Task.delay { println("finalized!") }).
          take(1).
          runLog.unsafeRun()
```

That covers synchronous interrupts. Let's look at asynchronous interrupts. Ponder what the result of `merged` will be in this example:

```tut
val s1 = (Stream(1) ++ Stream(2)).covary[Task]
val s2 = (Stream.empty ++ Stream.fail(Err)) onError { e => println(e); Stream.fail(e) }
val merged = s1 merge s2 take 1
```

The result is highly nondeterministic. Here are a few ways it can play out:

* `s1` may complete before the error in `s2` is encountered, in which case nothing will be printed and no error will occur.
* `s2` may encounter the error before any of `s1` is emitted. When the error is reraised by `s2`, that will terminate the `merge` and asynchronously interrupt `s1`, and the `take` terminates with that same error.
* `s2` may encounter the error before any of `s1` is emitted, but during the period where the value is caught by `onError`, `s1` may emit a value and the `take(1)` may terminate, triggering interruption of both `s1` and `s2`, before the error is reraised but after the exception is printed! In this case, the stream will still terminate without error.

The correctness of your program should not depend on how different streams interleave, and once again, you should not use `onError` or other interruptible functions for resource cleanup. Use `bracket` or `onFinalize` for this purpose.
