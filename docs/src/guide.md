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
* [Appendix: How interruption of streams works](#a1)

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

The `s1` stream has the type `Stream[Pure,Int]`. It's output type is of course `Int`, and its effect type is `Pure`, which means it does not require evaluation of any effects to produce its output. Streams that don't use any effects are called _pure_ streams. You can convert a pure stream to a `List` or `Vector` using:

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

Of these, only `flatMap` is primitive, the rest are built using combinations of various other primitives. We'll take a look at how that works shortly.

So far, we've just looked at pure streams. FS2 streams can also include evaluation of effects:

```tut:book
import cats.effect.IO

val eff = Stream.eval(IO { println("BEING RUN!!"); 1 + 1 })
```

`IO` is an effect type we'll see a lot in these examples. Creating an `IO` has no side effects, and `Stream.eval` doesn't do anything at the time of creation, it's just a description of what needs to happen when the stream is eventually interpreted. Notice the type of `eff` is now `Stream[IO,Int]`.

The `eval` function works for any effect type, not just `IO`. FS2 does not care what effect type you use for your streams. You may use `IO` for effects or bring your own, just by implementing a few interfaces for your effect type (`cats.effect.MonadError[?, Throwable]`, `cats.effect.Sync`, `cats.effect.Async`, and `cats.effect.Effect` if you wish to use various concurrent operations discussed later). Here's the signature of `eval`:

```scala
def eval[F[_],A](f: F[A]): Stream[F,A]
```

`eval` produces a stream that evaluates the given effect, then emits the result (notice that `F` is unconstrained). Any `Stream` formed using `eval` is called 'effectful' and can't be run using `toList` or `toVector`. If we try we'll get a compile error:

```tut:fail
eff.toList
```

Here's a complete example of running an effectful stream. We'll explain this in a minute:

```tut
eff.runLog.unsafeRunSync()
```

The first `.runLog` is one of several methods available to 'run' (or perhaps 'compile') the stream to a single effect:

```tut:book
val eff = Stream.eval(IO { println("TASK BEING RUN!!"); 1 + 1 })

val ra = eff.runLog // gather all output into a Vector
val rb = eff.run // purely for effects
val rc = eff.runFold(0)(_ + _) // run and accumulate some result
```

Notice these all return a `IO` of some sort, but this process of compilation doesn't actually _perform_ any of the effects (nothing gets printed).

If we want to run these for their effects 'at the end of the universe', we can use one of the `unsafe*` methods on `IO` (if you are bringing your own effect type, how you run your effects may of course differ):

```tut
ra.unsafeRunSync()
rb.unsafeRunSync()
rc.unsafeRunSync()
rc.unsafeRunSync()
```

Here we finally see the tasks being executed. As is shown with `rc`, rerunning a task executes the entire computation again; nothing is cached for you automatically.

_Note:_ The various `run*` functions aren't specialized to `IO` and work for any `F[_]` with an implicit `Effect[F]` (or `Sync[F]` for the `run*Sync` functions) --- FS2 needs to know how to catch errors that occur during evaluation of `F` effects, how to suspend computations, and sometimes how to do asynchronous evaluation.

### Segments & Chunks

FS2 streams are segmented internally for performance. You can construct an individual stream segment using `Stream.segment`, which accepts an `fs2.Segment` and lots of functions in the library are segment-aware and/or try to preserve segments when possible.

Segments are potentially infinite and support lazy, fused operations. A `Chunk` is a specialized segment that's finite and supports efficient indexed based lookup of elements.

```tut
import fs2.Chunk

val s1c = Stream.chunk(Chunk.doubles(Array(1.0, 2.0, 3.0)))

s1c.mapChunks { ds =>
  val doubles = ds.toDoubles
  /* do things unboxed using doubles.{values,size} */
 doubles
}
```

### Basic stream operations

Streams have a small but powerful set of operations, some of which we've seen already. The key operations are `++`, `map`, `flatMap`, `handleErrorWith`, and `bracket`:

```tut
val appendEx1 = Stream(1,2,3) ++ Stream.emit(42)
val appendEx2 = Stream(1,2,3) ++ Stream.eval(IO.pure(4))

appendEx1.toVector
appendEx2.runLog.unsafeRunSync()

appendEx1.map(_ + 1).toList
```

The `flatMap` operation is the same idea as lists - it maps, then concatenates:

```tut
appendEx1.flatMap(i => Stream.emits(List(i,i))).toList
```

Regardless of how a `Stream` is built up, each operation takes constant time. So `s ++ s2` takes constant time, regardless of whether `s` is `Stream.emit(1)` or it's a huge stream with millions of elements and lots of embedded effects. Likewise with `s.flatMap(f)` and `handleErrorWith`, which we'll see in a minute. The runtime of these operations do not depend on the structure of `s`.

### Error handling

A stream can raise errors, either explicitly, using `Stream.raiseError`, or implicitly via an exception in pure code or inside an effect passed to `eval`:

```tut
val err = Stream.raiseError(new Exception("oh noes!"))
val err2 = Stream(1,2,3) ++ (throw new Exception("!@#$"))
val err3 = Stream.eval(IO(throw new Exception("error in effect!!!")))
```

All these fail when running:

```tut
try err.toList catch { case e: Exception => println(e) }
```

```tut
try err2.toList catch { case e: Exception => println(e) }
```

```tut
try err3.run.unsafeRunSync() catch { case e: Exception => println(e) }
```

The `handleErrorWith` method lets us catch any of these errors:

```tut
err.handleErrorWith { e => Stream.emit(e.getMessage) }.toList
```

_Note: Don't use `handleErrorWith` for doing resource cleanup; use `bracket` as discussed in the next section. Also see [this section of the appendix](#a1) for more details._

### Resource acquisition

If you have to acquire a resource and want to guarantee that some cleanup action is run if the resource is acquired, use the `bracket` function:

```tut
val count = new java.util.concurrent.atomic.AtomicLong(0)
val acquire = IO { println("incremented: " + count.incrementAndGet); () }
val release = IO { println("decremented: " + count.decrementAndGet); () }
```

```tut:fail
Stream.bracket(acquire)(_ => Stream(1,2,3) ++ err, _ => release).run.unsafeRunSync()
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
Stream.eval_(IO(println("!!"))).runLog.unsafeRunSync()
(Stream(1,2) ++ (throw new Exception("nooo!!!"))).attempt.toList
```

### Statefully transforming streams

We often wish to statefully transform one or more streams in some way, possibly evaluating effects as we do so. As a running example, consider taking just the first 5 elements of a `s: Stream[IO,Int]`. To produce a `Stream[IO,Int]` which takes just the first 5 elements of `s`, we need to repeatedly await (or pull) values from `s`, keeping track of the number of values seen so far and stopping as soon as we hit 5 elements. In more complex scenarios, we may want to evaluate additional effects as we pull from one or more streams.

Let's look at an implementation of `take` using the `scanSegmentsOpt` combinator:

```tut:book
import fs2._

def tk[F[_],O](n: Long): Pipe[F,O,O] =
  in => in.scanSegmentsOpt(n) { n =>
    if (n <= 0) None
    else Some(seg => seg.take(n).mapResult {
      case Left((_,n)) => n
      case Right(_) => 0
    })
  }

Stream(1,2,3,4).through(tk(2)).toList
```

Let's take this line by line.

```scala
in => in.scanSegmentsOpt(n) { n =>
```

Here we create an anonymous function from `Stream[F,O]` to `Stream[F,O]` and we call `scanSegmentsOpt` passing an initial state of `n` and a function which we define on subsequent lines. The function takes the current state as an argument, which we purposefully give the name `n`, shadowing the `n` defined in the signature of `tk`, to make sure we can't accidentally reference it.

```scala
if (n <= 0) None
```

If the current state value is 0 (or less), we're done so we return `None`. This indicates to `scanSegmentsOpt` that the stream should terminate.

```scala
else Some(seg => seg.take(n).mapResult {
  case Left((_,n)) => n
  case Right(_) => 0
})
```

Otherwise, we return a function which processes the next segment in the stream. The function is implemented by taking `n` elements from the segment. `seg.take(n)` returns a `Segment[O,Either[(Unit,Long),Segment[O,Unit]]]`. We map over the result of the segment and pattern match on the either. If we encounter a `Left`, we discard the unit result and return the new remainder `n` returned from `take`. If instead we encounter a `Right`, we discard the remaining elements in the segment and return 0.

Sometimes, `scanSegmentsOpt` isn't powerful enough to express the stream transformation. Regardless of how complex the job, the `fs2.Pull` type can usually express it.

The `Pull[+F[_],+O,+R]` type represents a program that may pull values from one or more streams, write _output_ of type `O`, and return a _result_ of type `R`. It forms a monad in `R` and comes equipped with lots of other useful operations. See the [`Pull` class](../core/shared/src/main/scala/fs2/Pull.scala) for the full set of operations on `Pull`.

Let's look at an implementation of `take` using `Pull`:

```tut:book
import fs2._

def tk[F[_],O](n: Long): Pipe[F,O,O] = {
  def go(s: Stream[F,O], n: Long): Pull[F,O,Unit] = {
    s.pull.uncons.flatMap {
      case Some((hd,tl)) =>
        Pull.segment(hd.take(n)).flatMap {
          case Left((_,rem)) => go(tl,rem)
          case Right(_) => Pull.done
        }
      case None => Pull.done
    }
  }
  in => go(in,n).stream
}

Stream(1,2,3,4).through(tk(2)).toList
```

Taking this line by line:

```scala
def go(s: Stream[F,O], n: Long): Pull[F,O,Unit] = {
```

We implement this with a recursive function that returns a `Pull`. On each invocation, we provide a `Stream[F,O]` and the number of elements remaining to take `n`.

```scala
s.pull.uncons.flatMap {
```

Calling `s.pull` gives us a variety of methods which convert the stream to a `Pull`. We use `uncons` to pull the next segment from the stream, giving us a `Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]]`. We then `flatMap` in to that pull to access the option.

```scala
case Some((hd,tl)) =>
  Pull.segment(hd.take(n)).flatMap {
    case Left((_,rem)) => go(tl,rem)
    case Right(_) => Pull.done
  }
```

If we receive a `Some`, we destructure the tuple as `hd: Segment[O,Unit]` and `tl: Stream[F,O]`. We call then call `hd.take(n)`, resulting in a `Segment[O,Either[(Unit,Long),Segment[O,Unit]]]`. We lift that segment in to `Pull` via `Pull.segment`, which binds the output type of the pull to `O`, resulting in the output elements of the segment being output to the resulting pull. We then `flatMap` the resulting pull, giving us access to the result of the `take` on the head segment. If the result was a left, we call `go` recursively on the tail stream with the new remainder. If it was a right, we're done.

```scala
in => go(in,n).stream
```

Finally, we create an anonymous function from `Stream[F,O]` to `Stream[F,O]` and call `go` with the initial `n` value. We're returned a `Pull[F,O,Unit]`, which we convert back to a `Stream[F,O]` via the `.stream` method.

```tut
val s2 = Stream(1,2,3,4).through(tk(2))
s2.toList
```

FS2 takes care to guarantee that any resources allocated by the `Pull` are released when the `.stream` completes. Note again that _nothing happens_ when we call `.stream` on a `Pull`, it is merely establishing a scope in which all resource allocations are tracked so that they may be appropriately freed.

There are lots of useful transformation functions in [`Stream`](../core/shared/src/main/scala/fs2/Stream.scala) built using the `Pull` type.

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
Stream(1,2,3).merge(Stream.eval(IO { Thread.sleep(200); 4 })).runLog.unsafeRunSync()
```

Oops, we need a `scala.concurrent.ExecutionContext` in implicit scope. Let's add that:

```tut
import scala.concurrent.ExecutionContext.Implicits.global

Stream(1,2,3).merge(Stream.eval(IO { Thread.sleep(200); 4 })).runLog.unsafeRunSync()
```

The `merge` function supports concurrency. FS2 has a number of other useful concurrency functions like `concurrently` (runs another stream concurrently and discards its output), `interrupt` (halts if the left branch produces `false`), `either` (like `merge` but returns an `Either`), `mergeHaltBoth` (halts if either branch halts), and others.

The function `join` runs multiple streams concurrently. The signature is:

```Scala
// note Effect[F] bound and ExecutionContext parameter
import scala.concurrent.ExecutionContext
import cats.effect.Effect
def join[F[_]:Effect,O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit ec: ExecutionContext): Stream[F,O]
```

It flattens the nested stream, letting up to `maxOpen` inner streams run at a time.

The `Effect` bound on `F` along with the `ExecutionContext` implicit parameter is required anywhere concurrency is used in the library. As mentioned earlier, users can bring their own effect types provided they also supply an `Effect` instance and have an `ExecutionContext` in implicit scope.

If you examine the implementations of the above functions, you'll see a few primitive functions used. Let's look at those. First, `unconsAsync` requests the next step of a stream asynchronously. Its signature is:

```Scala
def unconsAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Segment[O,Unit], Stream[F,O])]]] =
```

An `AsyncPull[F,A]` represents a running computation that will eventually yield an `A`. An `AsyncPull[F,A]` has a method `.pull`, of type `Pull[F,Nothing,A]` that can be used to block until the result is available. An `AsyncPull[F,A]` may be raced with another `AsyncPull` also --- see the implementation of `merge` for an example.

In addition, there are a number of other concurrency primitives---asynchronous queues, signals, and semaphores. See the [`async` package object](../core/shared/src/main/scala/fs2/async/async.scala) for more details. We'll make use of some of these in the next section when discussing how to talk to the external world.

### Exercises

Without looking at the implementations, try implementing `interrupt` and `mergeHaltBoth`:

```Scala
type Pipe2[F[_],-I,-I2,+O] = (Stream[F,I], Stream[F,I2]) => Stream[F,O]

/** Like `merge`, but halts as soon as _either_ branch halts. */
def mergeHaltBoth[F[_]:Effect,O](implicit ec: ExecutionContext): Pipe2[F,O,O,O] = (s1, s2) => ???

/**
 * Let through the `s2` branch as long as the `s1` branch is `false`,
 * listening asynchronously for the left branch to become `true`.
 * This halts as soon as either branch halts.
 */
def interrupt[F[_]:Effect,I](implicit ec: ExecutionContext): Pipe2[F,Boolean,I,I] = (s1, s2) => ???
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

val s = Stream.eval_(IO { destroyUniverse() }) ++ Stream("...moving on")
s.runLog.unsafeRunSync()
```

The way you bring synchronous effects into your effect type may differ. `Sync.delay` can be used for this generally, without committing to a particular effect:

```tut:book
import cats.effect.Sync

val T = Sync[IO]
val s = Stream.eval_(T.delay { destroyUniverse() }) ++ Stream("...moving on")
s.runLog.unsafeRunSync()
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

That is, we provide a `Connection` with two callbacks (or a single callback that accepts an `Either`), and at some point later, the callback will be invoked _once_. The `cats.effect.Async` trait provides a handy function in these situations:

```Scala
trait Async[F[_]] extends MonadError[F, Throwable] {
  ...
  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => Unit): F[A]
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

// Effect extends both Sync and Async
val T = cats.effect.Effect[IO]
val bytes = T.async[Array[Byte]] { (cb: Either[Throwable,Array[Byte]] => Unit) =>
  c.readBytesE(cb)
}

Stream.eval(bytes).map(_.toList).runLog.unsafeRunSync()
```

Be sure to check out the [`fs2.io`](../io) package which has nice FS2 bindings to Java NIO libraries, using exactly this approach.

#### Asynchronous effects (callbacks invoked multiple times)

The nice thing about callback-y APIs that invoke their callbacks once is that throttling/back-pressure can be handled within FS2 itself. If you don't want more values, just don't read them, and they won't be produced! But sometimes you'll be dealing with a callback-y API which invokes callbacks you provide it _more than once_. Perhaps it's a streaming API of some sort and it invokes your callback whenever new data is available. In these cases, you can use an asynchronous queue to broker between the nice stream processing world of FS2 and the external API, and use whatever ad hoc mechanism that API provides for throttling of the producer.

_Note:_ Some of these APIs don't provide any means of throttling the producer, in which case you either have accept possibly unbounded memory usage (if the producer and consumer operate at very different rates), or use blocking concurrency primitives like `fs2.async.boundedQueue` or the primitives in `java.util.concurrent`.

Let's look at a complete example:

```tut:book:reset
import fs2._
import fs2.async
import scala.concurrent.ExecutionContext
import cats.effect.{ Effect, IO }

type Row = List[String]

trait CSVHandle {
  def withRows(cb: Either[Throwable,Row] => Unit): Unit
}

def rows[F[_]](h: CSVHandle)(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Row] =
  for {
    q <- Stream.eval(async.unboundedQueue[F,Either[Throwable,Row]])
    _ <- Stream.suspend { h.withRows { e => async.unsafeRunAsync(q.enqueue1(e))(_ => IO.unit) }; Stream.emit(()) }
    row <- q.dequeue.rethrow
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

### <a id="a1"></a> Appendix A1: How interruption of streams works

In FS2, a stream can terminate in one of three ways:

1. Normal input exhaustion. For instance, the stream `Stream(1,2,3)` terminates after the single chunk (containing the values `1, 2, 3`) is emitted.
2. An uncaught exception. For instance, the stream `Stream(1,2,3) ++ (throw Err)` terminates with `Err` after the single chunk is emitted.
3. Interruption by the stream consumer. Interruption can be _synchronous_, as in `(Stream(1) ++ (throw Err)) take 1`, which will deterministically halt the stream before the `++`, or it can be _asynchronous_, as in `s1 merge s2 take 3`.

Regarding 3:

* A stream will never be interrupted while it is acquiring a resource (via `bracket`) or while it is releasing a resource. The `bracket` function guarantees that if FS2 starts acquiring the resource, the corresponding release action will be run.
* Other than that, Streams can be interrupted in between any two 'steps' of the stream. The steps themselves are atomic from the perspective of FS2. `Stream.eval(eff)` is a single step, `Stream.emit(1)` is a single step, `Stream(1,2,3)` is a single step (emitting a chunk), and all other operations (like `handleErrorWith`, `++`, and `flatMap`) are multiple steps and can be interrupted. But importantly, user-provided effects that are passed to `eval` are never interrupted once they are started (and FS2 does not have enough knowledge of user-provided effects to know how to interrupt them anyway).
* _Always use `bracket` or a `bracket`-based function like `onFinalize` for supplying resource cleanup logic or any other logic you want to be run regardless of how the stream terminates. Don't use `handleErrorWith` or `++` for this purpose._

Let's look at some examples of how this plays out, starting with the synchronous interruption case:

```tut
case object Err extends Throwable

(Stream(1) ++ (throw Err)).take(1).toList
(Stream(1) ++ Stream.raiseError(Err)).take(1).toList
```

The `take 1` uses `Pull` but doesn't examine the entire stream, and neither of these examples will ever throw an error. This makes sense. A bit more subtle is that this code will _also_ never throw an error:

```tut
(Stream(1) ++ Stream.raiseError(Err)).take(1).toList
```

The reason is simple: the consumer (the `take(1)`) terminates as soon as it has an element. Once it has that element, it is done consuming the stream and doesn't bother running any further steps of it, so the stream never actually completes normally---it has been interrupted before that can occur. We may be able to see in this case that nothing follows the emitted `1`, but FS2 doesn't know this until it actually runs another step of the stream.

If instead we use `onFinalize`, the code is guaranteed to run, regardless of whether `take` interrupts:

```tut:book
Stream(1).covary[IO].
          onFinalize(IO { println("finalized!") }).
          take(1).
          runLog.unsafeRunSync()
```

That covers synchronous interrupts. Let's look at asynchronous interrupts. Ponder what the result of `merged` will be in this example:

```tut
import scala.concurrent.ExecutionContext.Implicits.global
val s1 = (Stream(1) ++ Stream(2)).covary[IO]
val s2 = (Stream.empty ++ Stream.raiseError(Err)) handleErrorWith { e => println(e); Stream.raiseError(e) }
val merged = s1 merge s2 take 1
```

The result is highly nondeterministic. Here are a few ways it can play out:

* `s1` may complete before the error in `s2` is encountered, in which case nothing will be printed and no error will occur.
* `s2` may encounter the error before any of `s1` is emitted. When the error is reraised by `s2`, that will terminate the `merge` and asynchronously interrupt `s1`, and the `take` terminates with that same error.
* `s2` may encounter the error before any of `s1` is emitted, but during the period where the value is caught by `handleErrorWith`, `s1` may emit a value and the `take(1)` may terminate, triggering interruption of both `s1` and `s2`, before the error is reraised but after the exception is printed! In this case, the stream will still terminate without error.

The correctness of your program should not depend on how different streams interleave, and once again, you should not use `handleErrorWith` or other interruptible functions for resource cleanup. Use `bracket` or `onFinalize` for this purpose.
