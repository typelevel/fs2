<!--
This markdown file contains code examples which can be compiled using mdoc. Switch to `project docs`, then do `mdoc`. Output is produced in `docs/`.
-->

# FS2: The Official Guide

This is the official FS2 guide. It gives an overview of the library and its features and it's kept up to date with the code. If you spot a problem with this guide, a nonworking example, or simply have some suggested improvements, open a pull request! It's very much a WIP.

### Table of contents

* [Overview](#overview)
* [Building streams](#building-streams)
* [Chunks](#chunks)
* [Basic stream operations](#basic-stream-operations)
* [Error handling](#error-handling)
* [Resource acquisition](#resource-acquisition)
* [Exercises (stream building)](#exercises-stream-building)
* [Statefully transforming streams](#statefully-transforming-streams)
* [Exercises (stream transforming)](#exercises-stream-transforming)
* [Concurrency](#concurrency)
* [Exercises (concurrency)](#exercises-concurrency)
* [Interruption](#interruption)
* [Talking to the external world](#talking-to-the-external-world)
* [Reactive streams](#reactive-streams)
* [Learning more](#learning-more)
* [Appendixes](#appendixes)

_Unless otherwise noted, the type `Stream` mentioned in this document refers to the type `fs2.Stream` and NOT `scala.collection.immutable.Stream`._

### Overview

The FS2 library has two major capabilities:

* The ability to _build_ arbitrarily complex streams, possibly with embedded effects.
* The ability to _transform_ one or more streams using a small but powerful set of operations

We'll consider each of these in this guide.

### Building streams

A `Stream[F,O]` represents a discrete stream of `O` values which may request evaluation of `F` effects. We'll call `F` the _effect type_ and `O` the _output type_. Let's look at some examples:

```scala
import fs2.Stream

val s0 = Stream.empty
// s0: Stream[fs2.package.Pure, Nothing] = Stream(..)
val s1 = Stream.emit(1)
// s1: Stream[[x]fs2.package.Pure[x], Int] = Stream(..)
val s1a = Stream(1,2,3) // variadic
// s1a: Stream[[x]fs2.package.Pure[x], Int] = Stream(..)
val s1b = Stream.emits(List(1,2,3)) // accepts any Seq
// s1b: Stream[[x]fs2.package.Pure[x], Int] = Stream(..)
```

The `s1` stream has the type `Stream[Pure,Int]`. Its output type is of course `Int`, and its effect type is `Pure`, which means it does not require evaluation of any effects to produce its output. Streams that don't use any effects are called _pure_ streams. You can convert a pure stream to a `List` or `Vector` using:

```scala
s1.toList
// res0: List[Int] = List(1)
s1.toVector
// res1: Vector[Int] = Vector(1)
```

Streams have lots of handy 'list-like' functions. Here's a very small sample:

```scala
(Stream(1,2,3) ++ Stream(4,5)).toList
// res2: List[Int] = List(1, 2, 3, 4, 5)
Stream(1,2,3).map(_ + 1).toList
// res3: List[Int] = List(2, 3, 4)
Stream(1,2,3).filter(_ % 2 != 0).toList
// res4: List[Int] = List(1, 3)
Stream(1,2,3).fold(0)(_ + _).toList
// res5: List[Int] = List(6)
Stream(None,Some(2),Some(3)).collect { case Some(i) => i }.toList
// res6: List[Int] = List(2, 3)
Stream.range(0,5).intersperse(42).toList
// res7: List[Int] = List(0, 42, 1, 42, 2, 42, 3, 42, 4)
Stream(1,2,3).flatMap(i => Stream(i,i)).toList
// res8: List[Int] = List(1, 1, 2, 2, 3, 3)
Stream(1,2,3).repeat.take(9).toList
// res9: List[Int] = List(1, 2, 3, 1, 2, 3, 1, 2, 3)
Stream(1,2,3).repeatN(2).toList
// res10: List[Int] = List(1, 2, 3, 1, 2, 3)
```

Of these, only `flatMap` is primitive, the rest are built using combinations of various other primitives. We'll take a look at how that works shortly.

So far, we've just looked at pure streams. FS2 streams can also include evaluation of effects:

```scala
import cats.effect.IO

val eff = Stream.eval(IO { println("BEING RUN!!"); 1 + 1 })
// eff: Stream[IO, Int] = Stream(..)
```

`IO` is an effect type we'll see a lot in these examples. Creating an `IO` has no side effects, and `Stream.eval` doesn't do anything at the time of creation, it's just a description of what needs to happen when the stream is eventually interpreted. Notice the type of `eff` is now `Stream[IO,Int]`.

The `eval` function works for any effect type, not just `IO`. FS2 does not care what effect type you use for your streams. You may use `IO` for effects or bring your own, just by implementing a few interfaces for your effect type (e.g., `cats.MonadError[?, Throwable]`, `cats.effect.Sync`, `cats.effect.Async`, `cats.effect.Concurrent`). Here's the signature of `eval`:

```scala
def eval[F[_],A](f: F[A]): Stream[F,A]
```

`eval` produces a stream that evaluates the given effect, then emits the result (notice that `F` is unconstrained). Any `Stream` formed using `eval` is called 'effectful' and can't be run using `toList` or `toVector`. If we try we'll get a compile error:

```scala
eff.toList
// error: value toList is not a member of fs2.Stream[cats.effect.IO,Int]
// eff.toList
//     ^^^^^^
```

Here's a complete example of running an effectful stream. We'll explain this in a minute:

```scala
import cats.effect.unsafe.implicits.global

eff.compile.toVector.unsafeRunSync()
// BEING RUN!!
// res12: Vector[Int] = Vector(2)
```

The first `.compile.toVector` is one of several methods available to 'compile' the stream to a single effect:

```scala
val ra = eff.compile.toVector // gather all output into a Vector
// ra: IO[Vector[Int]] = IO(...)
val rb = eff.compile.drain // purely for effects
// rb: IO[Unit] = IO(...)
val rc = eff.compile.fold(0)(_ + _) // run and accumulate some result
// rc: IO[Int] = IO(...)
```

Notice these all return a `IO` of some sort, but this process of compilation doesn't actually _perform_ any of the effects (nothing gets printed).

If we want to run these for their effects 'at the end of the universe', we can use one of the `unsafe*` methods on `IO` (if you are bringing your own effect type, how you run your effects may of course differ):

```scala
ra.unsafeRunSync()
// BEING RUN!!
// res13: Vector[Int] = Vector(2)
rb.unsafeRunSync()
// BEING RUN!!
rc.unsafeRunSync()
// BEING RUN!!
// res15: Int = 2
rc.unsafeRunSync()
// BEING RUN!!
// res16: Int = 2
```

Here we finally see the tasks being executed. As is shown with `rc`, rerunning a task executes the entire computation again; nothing is cached for you automatically.

### Chunks

FS2 streams are chunked internally for performance. You can construct an individual stream chunk using `Stream.chunk`, which accepts an `fs2.Chunk` and lots of functions in the library are chunk-aware and/or try to preserve chunks when possible. A `Chunk` is a strict, finite sequence of values that supports efficient indexed based lookup of elements.

```scala
import fs2.Chunk

val s1c = Stream.chunk(Chunk.array(Array(1.0, 2.0, 3.0)))
// s1c: Stream[[x]fs2.package.Pure[x], Double] = Stream(..)
```

### Basic stream operations

Streams have a small but powerful set of operations, some of which we've seen already. The key operations are `++`, `map`, `flatMap`, `handleErrorWith`, and `bracket`:

```scala
val appendEx1 = Stream(1,2,3) ++ Stream.emit(42)
// appendEx1: Stream[[x]fs2.package.Pure[x], Int] = Stream(..)
val appendEx2 = Stream(1,2,3) ++ Stream.eval(IO.pure(4))
// appendEx2: Stream[[A]IO[A], Int] = Stream(..)

appendEx1.toVector
// res17: Vector[Int] = Vector(1, 2, 3, 42)
appendEx2.compile.toVector.unsafeRunSync()
// res18: Vector[Int] = Vector(1, 2, 3, 4)

appendEx1.map(_ + 1).toList
// res19: List[Int] = List(2, 3, 4, 43)
```

The `flatMap` operation is the same idea as lists - it maps, then concatenates:

```scala
appendEx1.flatMap(i => Stream.emits(List(i,i))).toList
// res20: List[Int] = List(1, 1, 2, 2, 3, 3, 42, 42)
```

Regardless of how a `Stream` is built up, each operation takes constant time. So `s ++ s2` takes constant time, regardless of whether `s` is `Stream.emit(1)` or it's a huge stream with millions of elements and lots of embedded effects. Likewise with `s.flatMap(f)` and `handleErrorWith`, which we'll see in a minute. The runtime of these operations do not depend on the structure of `s`.

### Error handling

A stream can raise errors, either explicitly, using `Stream.raiseError`, or implicitly via an exception in pure code or inside an effect passed to `eval`:

```scala
val err = Stream.raiseError[IO](new Exception("oh noes!"))
// err: Stream[IO, Nothing] = Stream(..)
val err2 = Stream(1,2,3) ++ (throw new Exception("!@#$"))
// err2: Stream[[x]fs2.package.Pure[x], Int] = Stream(..)
val err3 = Stream.eval(IO(throw new Exception("error in effect!!!")))
// err3: Stream[IO, Nothing] = Stream(..)
```

All these fail when running:

```scala
try err.compile.toList.unsafeRunSync() catch { case e: Exception => println(e) }
// java.lang.Exception: oh noes!
// res21: Any = ()
```

```scala
try err2.toList catch { case e: Exception => println(e) }
// java.lang.Exception: !@#$
// res22: Any = ()
```

```scala
try err3.compile.drain.unsafeRunSync() catch { case e: Exception => println(e) }
// java.lang.Exception: error in effect!!!
```

The `handleErrorWith` method lets us catch any of these errors:

```scala
err.handleErrorWith { e => Stream.emit(e.getMessage) }.compile.toList.unsafeRunSync()
// res24: List[String] = List("oh noes!")
```

Note that even when using `handleErrorWith` (or `attempt`) the stream will be terminated after the error and no more values will be pulled. In the following example, the integer 4 is never pulled from the stream.

```scala
val err4 = Stream(1,2,3).covary[IO] ++
  Stream.raiseError[IO](new Exception("bad things!")) ++
  Stream.eval(IO(4))
// err4: Stream[[x]IO[x], Int] = Stream(..)

err4.handleErrorWith { _ => Stream(0) }.compile.toList.unsafeRunSync()
// res25: List[Int] = List(1, 2, 3, 0)
```

_Note: Don't use `handleErrorWith` for doing resource cleanup; use `bracket` as discussed in the next section. Also see [this section of the appendix](#a1) for more details._

### Resource acquisition

If you have to acquire a resource and want to guarantee that some cleanup action is run if the resource is acquired, use the `bracket` function:

```scala
val count = new java.util.concurrent.atomic.AtomicLong(0)
// count: java.util.concurrent.atomic.AtomicLong = 0
val acquire = IO { println("incremented: " + count.incrementAndGet); () }
// acquire: IO[Unit] = IO(...)
val release = IO { println("decremented: " + count.decrementAndGet); () }
// release: IO[Unit] = IO(...)
```

```scala
Stream.bracket(acquire)(_ => release).flatMap(_ => Stream(1,2,3) ++ err).compile.drain.unsafeRunSync()
// java.lang.Exception: oh noes!
// 	at repl.MdocSession$MdocApp.<init>(guide.md:149)
// 	at repl.MdocSession$.app(guide.md:3)
```

The inner stream fails, but notice the `release` action is still run:

```scala
count.get
// res26: Long = 0L
```

No matter how you transform an FS2 `Stream` or where any errors occur, the library guarantees that if the resource is acquired via a `bracket`, the release action associated with that `bracket` will be run. Here's the signature of `bracket`:

```scala
def bracket[F[_], R](acquire: F[R])(release: R => F[Unit]): Stream[F, R]
```

FS2 guarantees _once and only once_ semantics for resource cleanup actions introduced by the `Stream.bracket` function.

### Exercises Stream Building

Implement `repeat`, which repeats a stream indefinitely, `drain`, which strips all output from a stream, `exec`, which runs an effect and ignores its output, and `attempt`, which catches any errors produced by a stream:

```scala
Stream(1,0).repeat.take(6).toList
// res27: List[Int] = List(1, 0, 1, 0, 1, 0)
Stream(1,2,3).drain.toList
// res28: List[Nothing] = List()
Stream.exec(IO.println("!!")).compile.toVector.unsafeRunSync()
// res29: Vector[Nothing] = Vector()
(Stream(1,2) ++ Stream(3).map(_ => throw new Exception("nooo!!!"))).attempt.toList
// res30: List[Either[Throwable, Int]] = List(
//   Right(value = 1),
//   Right(value = 2),
//   Left(value = java.lang.Exception: nooo!!!)
// )
```

### Statefully transforming streams

We often wish to statefully transform one or more streams in some way, possibly evaluating effects as we do so. As a running example, consider taking just the first 5 elements of a `s: Stream[IO,Int]`. To produce a `Stream[IO,Int]` which takes just the first 5 elements of `s`, we need to repeatedly await (or pull) values from `s`, keeping track of the number of values seen so far and stopping as soon as we hit 5 elements. In more complex scenarios, we may want to evaluate additional effects as we pull from one or more streams.

Let's look at an implementation of `take` using the `scanChunksOpt` combinator:

```scala
import fs2._

def tk[F[_],O](n: Long): Pipe[F,O,O] =
  in => in.scanChunksOpt(n) { n =>
    if (n <= 0) None
    else Some(c => c.size match {
      case m if m < n => (n - m, c)
      case _ => (0, c.take(n.toInt))
    })
  }

Stream(1,2,3,4).through(tk(2)).toList
// res32: List[Int] = List(1, 2)
```

Let's take this line by line.

```scala
in => in.scanChunksOpt(n) { n =>
```

Here we create an anonymous function from `Stream[F,O]` to `Stream[F,O]` and we call `scanChunksOpt` passing an initial state of `n` and a function which we define on subsequent lines. The function takes the current state as an argument, which we purposefully give the name `n`, shadowing the `n` defined in the signature of `tk`, to make sure we can't accidentally reference it.

```scala
if (n <= 0) None
```

If the current state value is 0 (or less), we're done so we return `None`. This indicates to `scanChunksOpt` that the stream should terminate.

```scala
else Some(c => c.size match {
  case m if m < n => (n - m, c)
  case m => (0, c.take(n.toInt))
})
```

Otherwise, we return a function which processes the next chunk in the stream. The function first checks the size of the chunk. If it is less than the number of elements to take, it returns the chunk unmodified, causing it to be output downstream, along with the number of remaining elements to take from subsequent chunks (`n - m`). If instead, the chunks size is greater than the number of elements left to take, `n` elements are taken from the chunk and output, along with an indication that there are no more elements to take.

#### Transforming streams using pulls

Sometimes, `scanChunksOpt` isn't powerful enough to express the stream transformation. Regardless of how complex the job, the `fs2.Pull` type can usually express it.

The `Pull[F[_],O,R]` type represents a program that may pull values from one or more streams, write _output_ of type `O`, and return a _result_ of type `R`. It forms a monad in `R` and comes equipped with lots of other useful operations. See the
[`Pull` class](https://github.com/functional-streams-for-scala/fs2/blob/main/core/shared/src/main/scala/fs2/Pull.scala)
for the full set of operations on `Pull`.

A pull that writes a single output of type `Int` can be constructed with `Pull.output1`.

```scala
import fs2._
val p1 = Pull.output1(1)
// p1: Pull[Nothing, Int, Unit] = Output(values = Chunk(1))
```

This can be converted directly to a stream equivalent to `Stream(1)`.

```scala
val s1 = p1.stream
// s1: Stream[Nothing, Int] = Stream(..)
```

Pulls form a monad in their result `R` and can be composed using monadic operations. The following code produces a `Pull` corresponding to `Stream(1, 2)`.

```scala
p1 >> Pull.output1(2)
// res34: Pull[Nothing, Int, Unit] = <function1>
```

A `Pull` can be created from a stream using a variety of operations accessed under the `pull` function. For example `echo` converts a stream to its corresponding pull representation.

```scala
s1.pull.echo
// res35: Pull[Nothing, Int, Unit] = InScope(
//   stream = Output(values = Chunk(1)),
//   useInterruption = false
// )
```


A more useful pull is created by `uncons`. This constructs a pull that pulls the next chunk from the stream.

```scala
s1.pull.uncons
// res36: Pull[Nothing, Nothing, Option[(Chunk[Int], Stream[Nothing, Int])]] = <function1>
```

Let’s examine its result type.
 - The `Option` is non-empty if there is a chunk to pull from the stream. If the stream has terminated and there are no more chunks to pull then the result is `None`.
 - `Chunk[Int]` represents the pulled chunk.
 - `Stream[Nothing, Int]` represents the tail of the stream.

Note that the output type is `Nothing` because the pull does not output any elements.

Let's look at an implementation of `take` using `uncons`:

```scala
import fs2._

def tk[F[_],O](n: Long): Pipe[F,O,O] = {
  def go(s: Stream[F,O], n: Long): Pull[F,O,Unit] = {
    s.pull.uncons.flatMap {
      case Some((hd,tl)) =>
        hd.size match {
          case m if m <= n => Pull.output(hd) >> go(tl, n - m)
          case _ => Pull.output(hd.take(n.toInt))
        }
      case None => Pull.done
    }
  }
  in => go(in,n).stream
}

Stream(1,2,3,4).through(tk(2)).toList
// res38: List[Int] = List(1, 2)
```

Taking this line by line:

```scala
def go(s: Stream[F,O], n: Long): Pull[F,O,Unit] = {
```

We implement this with a recursive function that returns a `Pull`. On each invocation, we provide a `Stream[F,O]` and the number of elements remaining to take `n`.

```scala
s.pull.uncons.flatMap {
```

Calling `s.pull` gives us a variety of methods which convert the stream to a `Pull`. We use `uncons` to pull the next chunk from the stream, giving us a `Pull[F,Nothing,Option[(Chunk[O],Stream[F,O])]]`. We then `flatMap` in to that pull to access the option.

```scala
case Some((hd,tl)) =>
  hd.size match {
    case m if m <= n => Pull.output(hd) >> go(tl, n - m)
    case m => Pull.output(hd.take(n.toInt))
  }
```

If we receive a `Some`, we destructure the tuple as `hd: Chunk[O]` and `tl: Stream[F,O]`. We then check the size of the head chunk. If the chunk size is less than or equal to the remaining elements to take, the chunk is output via `Pull.output` and we then recurse on the tail of the stream `tl` by calling `go`, passing the remaining elements to take. Otherwise we output the first `n` elements of the head.

```scala
case None => Pull.done
```

A `None` is received when there are no more elements to pull from the `in` stream. We finish pulling with `Pull.done`, a pull that does nothing.

```scala
in => go(in,n).stream
```

Finally, we create an anonymous function from `Stream[F,O]` to `Stream[F,O]` and call `go` with the initial `n` value. We're returned a `Pull[F,O,Unit]`, which we convert back to a `Stream[F,O]` via the `.stream` method.

```scala
val s2 = Stream(1,2,3,4).through(tk(2))
// s2: Stream[[x]Pure[x], Int] = Stream(..)
s2.toList
// res39: List[Int] = List(1, 2)
```

FS2 takes care to guarantee that any resources allocated by the `Pull` are released when the stream completes. Note again that _nothing happens_ when we call `.stream` on a `Pull`, it is merely converting back to the `Stream` API.

In practise, explicit recursion is rarely necessary — the methods under `pull` usually recurse for us. The explicit recursion in `tk` could be removed by using `pull.take`.

```scala
import fs2._

def tk[F[_],O](n: Long): Pipe[F,O,O] = {
  in => in.pull.take(n).void.stream
}

Stream(1,2,3,4).through(tk(2)).toList
// res41: List[Int] = List(1, 2)
```

There are lots of useful transformation functions in
[`Stream`](https://github.com/functional-streams-for-scala/fs2/blob/main/core/shared/src/main/scala/fs2/Stream.scala)
built using the `Pull` type.

### Exercises Stream Transforming

Try implementing `takeWhile`, `intersperse`, and `scan`:

```scala
Stream.range(0,100).takeWhile(_ < 7).toList
// res42: List[Int] = List(0, 1, 2, 3, 4, 5, 6)
Stream("Alice","Bob","Carol").intersperse("|").toList
// res43: List[String] = List("Alice", "|", "Bob", "|", "Carol")
Stream.range(1,10).scan(0)(_ + _).toList // running sum
// res44: List[Int] = List(0, 1, 3, 6, 10, 15, 21, 28, 36, 45)
```

### Concurrency

FS2 comes with lots of concurrent operations. The `merge` function runs two streams concurrently, combining their outputs. It halts when both inputs have halted:

```scala
import cats.effect.IO

Stream(1,2,3).merge(Stream.eval(IO { Thread.sleep(200); 4 })).compile.toVector.unsafeRunSync()
// error: Could not find an implicit IORuntime.
// 
// Instead of calling unsafe methods directly, consider using cats.effect.IOApp, which
// runs your IO. If integrating with non-functional code or experimenting in a REPL / Worksheet,
// add the following import:
// 
// import cats.effect.unsafe.implicits.global
// 
// Alternatively, you can create an explicit IORuntime value and put it in implicit scope.
// This may be useful if you have a pre-existing fixed thread pool and/or scheduler which you
// wish to use to execute IO programs. Please be sure to review thread pool best practices to
// avoid unintentionally degrading your application performance.
// 
// Stream(1,2,3).merge(Stream.eval(IO { Thread.sleep(200); 4 })).compile.toVector.unsafeRunSync()
//                                                                                             ^
```

Oops, we need a `cats.effect.unsafe.IORuntime` in implicit scope. Let's add that:

```scala
import cats.effect.IO
import cats.effect.unsafe.implicits.global

Stream(1,2,3).merge(Stream.eval(IO { Thread.sleep(200); 4 })).compile.toVector.unsafeRunSync()
// res46: Vector[Int] = Vector(1, 2, 3, 4)
```

The `merge` function supports concurrency. FS2 has a number of other useful concurrency functions like `concurrently` (runs another stream concurrently and discards its output), `interruptWhen` (halts if the left branch produces `true`), `either` (like `merge` but returns an `Either`), and others.

Depending on how you want to halt the resulting stream, you can use either of the three variants of the `merge` method: `mergeHaltR`, `mergeHaltL`, and `mergeHaltBoth`. The resulting stream will terminate whenever the right stream halts, the left stream halts, or the stream on either side halts, respectively:

```scala 
val finite = Stream('a', 'b', 'c', 'd', 'e').covary[IO]
val infinite = Stream.iterate(0)(_ + 1).covary[IO]

// Left --------- Right
finite.mergeHaltL(infinite)     // Terminates
infinite.mergeHaltL(finite)     // Doesn't terminate

finite.mergeHaltR(infinite)     // Doesn't terminate
infinite.mergeHaltR(finite)     // Terminates

finite.mergeHaltBoth(infinite)  // Terminates
infinite.mergeHaltBoth(finite)  // Also terminates
```

Since it is quite common to terminate one stream as soon as the other is finished (as in a producer-consumer environment), there are optimizations over the `merge` variants. `concurrently` is one of them, as it will terminate the resulting stream when the stream on the left halts. The stream on the right will also terminate at that point, discarding its values in the meantime (similar to `finite.mergeHaltL(infinite.drain)`):

```scala
import cats.effect.std.{Queue, Random}
import scala.concurrent.duration._

def producer(queue: Queue[IO, Option[Int]])(implicit rnd: Random[IO]): Stream[IO, Option[Int]] = 
  Stream
    .repeatEval(Random[IO].betweenInt(100,800))
    .evalTap(n => IO.println(s"Produced: $n"))
    .flatMap(t => Stream.sleep[IO](t.milliseconds) >> Stream.emit(if (t >= 750) None else Some(t)))
    .evalTap(queue.offer)

def consumer(queue: Queue[IO, Option[Int]]): Stream[IO, Unit] = 
  Stream.fromQueueNoneTerminated(queue, 10).evalMap(n => IO.println(s"Consumed: $n"))

val concurrentlyDemo = 
  Stream.eval(Queue.bounded[IO, Option[Int]](20)).flatMap { queue =>
    Stream.eval(Random.scalaUtilRandom[IO]).flatMap { implicit rnd =>

      consumer(queue).concurrently(producer(queue))

    }
  }
// concurrentlyDemo: Stream[[x]IO[x], Unit] = Stream(..)

concurrentlyDemo.compile.drain.unsafeRunSync()
```

In the example above, the `consumer` stream will terminate if an element produced by the `producer` takes more than 750 milliseconds.


The `parEvalMap` function allows you to evaluate effects in parallel and emit the results in order on up to `maxConcurrent` fibers at the same time, similar to the `parTraverseN` method that you would normally use in standard library collections:

``` scala mdoc
// This will evaluate 5 effects in parallel
Stream(1, 2, 3, 4, 5).parEvalMap(5)(n => IO.pure(n * 2)).compile.toVector.unsafeRunSync()
```

However, its use with pure operations is rare; it is more common with functions or combinators that can have side effects:

```scala
import fs2.io.file.{Path, Files}
import fs2.io.readInputStream

import java.net.{URI, URL}
import java.io.InputStream

def getConnectionStream(url: URL): IO[InputStream] = IO(url.openConnection().getInputStream())

// The Adventures of Tom Sawyer by Mark Twain 
val bookParts = Stream(
  "7193/pg7193.txt", // Part 1
  "7194/pg7194.txt", // Part 2
  "7195/pg7195.txt", // Part 3
  "7196/pg7196.txt"  // Part 4
).map( part => new URI(s"https://www.gutenberg.org/cache/epub/$part").toURL() )
// bookParts: Stream[[x]Pure[x], URL] = Stream(..)

bookParts
  .covary[IO]
  .parEvalMap(4)(url => IO.println(s"Getting connection from $url") >> getConnectionStream(url))
  .flatMap(inps => readInputStream[IO](IO(inps), 4096))
  .through(Files[IO].writeAll(Path("testdata/tom_sawyer.txt")))
  .compile
  .drain
  .unsafeRunSync()
```

Although most of the time the order of the stream is not important. This may be the case for a number of reasons, such as if the resulting emitted values are not important, if the function you are passing may take significantly different amounts of time depending on the input provided, etcetera. For these cases there is a `parEvalMapUnordered` method. For example, if you just want to log the effects as soon as they're complete:

``` scala mdoc
def slowFibo(n: Int): Int = // Just to simulate an expensive computation
  if n <= 0 then n
  else if n == 1 then 1
  else slowFibo(n - 1) + slowFibo(n - 2)

Stream.eval(Random.scalaUtilRandom[IO]).flatMap { rnd =>
  Stream.repeatEval[IO, Int](rnd.nextIntBounded(40))
    .parEvalMapUnordered(2)(n => IO.println(s"Emitted value for $n with result: ${slowFibo(n)}"))
}
.interruptAfter(3.seconds)
.compile
.drain
.unsafeRunSync()
```

Note that if you want unbounded concurrency, there are also `parEvalMapUnbounded` and `parEvalMapUnorderedUnbounded` versions of these methods which do not take a `maxConcurrent` argument.

The function `parJoin` runs multiple streams concurrently, it is very useful when running multiple streams as independent processes instead of making them dependent on each other. The signature is:

```scala
// note Concurrent[F] bound
import cats.effect.Concurrent
def parJoin[F[_]: Concurrent,O](maxOpen: Int)(outer: Stream[F, Stream[F, O]]): Stream[F, O]
```

It flattens the nested stream, letting up to `maxOpen` inner streams run at a time. Like `parEvalMapUnbounded`, there is a `parJoinUnbounded` method if you need ubounded concurrency.

An example running three different processes at the same time using `parJoin`:

```scala
val processA = Stream.awakeEvery[IO](1.second).map(_ => 1).evalTap(x => IO.println(s"Process A: $x"))
// processA: Stream[[x]IO[x], Int] = Stream(..)

val processB = Stream.iterate[IO, Int](0)(_ + 1).metered(400.milliseconds).evalTap(x => IO.println(s"Process B: $x"))
// processB: Stream[[x]IO[x], Int] = Stream(..)

val processC = Stream(1, 2, 3, 4, 5)
  .flatMap(t => Stream.sleep[IO]((t * 200).milliseconds) >> Stream.emit(t))
  .repeat
  .evalTap(x => IO.println(s"Process C: $x"))
// processC: Stream[[x]IO[x], Int] = Stream(..)

Stream(processA, processB, processC).parJoin(3)
  .interruptAfter(5.seconds)
  .compile
  .drain
  .unsafeRunSync()
```

The `Concurrent` bound on `F` is required anywhere concurrency is used in the library. As mentioned earlier, users can bring their own effect types provided they also supply an `Concurrent` instance in implicit scope.

In addition, there are a number of other concurrency primitives---asynchronous queues, signals, and semaphores. See the [Concurrency Primitives section](concurrency-primitives.md#concurrency-primitives) for more examples. We'll make use of some of these in the next section when discussing how to talk to the external world.

### Exercises Concurrency

Without looking at the implementations, try implementing `mergeHaltBoth`:

```scala
type Pipe2[F[_],-I,-I2,+O] = (Stream[F,I], Stream[F,I2]) => Stream[F,O]

/** Like `merge`, but halts as soon as _either_ branch halts. */
def mergeHaltBoth[F[_]:Concurrent,O]: Pipe2[F,O,O,O] = (s1, s2) => ???
```

### Interruption

Sometimes some tasks have to run only when some conditions are met or until some other task completes. Luckily for us, `Stream` defines some really useful methods that let us accomplish this.
In the following example we will see how `interruptWhen` helps us to describe such cases. We will describe a program composed by two concurrent streams: the first will print the current time to the console every second, the second will stop the first.

First of all we will need to set up the environment with some imports and declare some implicit values.
```scala
import fs2.Stream
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
```

The example looks like this:
```scala
val program =
  Stream.eval(Deferred[IO, Unit]).flatMap { switch =>
    val switcher =
      Stream.eval(switch.complete(())).delayBy(5.seconds)

    val program =
      Stream.repeatEval(IO(println(java.time.LocalTime.now))).metered(1.second)

    program
      .interruptWhen(switch.get.attempt)
      .concurrently(switcher)
  }
// program: Stream[[x]IO[x], Unit] = Stream(..)

program.compile.drain.unsafeRunSync()
// 17:14:11.649848114
// 17:14:12.650703029
// 17:14:13.650345728
// 17:14:14.650031536
```

Let's take this line by line now, so we can understand what's going on.
```scala
val program =
  Stream.eval(Deferred[IO, Unit]).flatMap { switch =>
```

Here we create a `Stream[IO, Deferred[IO, Unit]]`. [`Deferred`](https://typelevel.org/cats-effect/concurrency/deferred.html) is a concurrency primitive that represents a condition yet to be fulfilled. We will use the emitted instance of `Deferred[IO, Unit]` as a mechanism to signal the completion of a task. Given this purpose, we call this instance `switch`.

```scala
val switcher =
  Stream.eval(switch.complete(())).delayBy(5.seconds)
```

The `switcher` will be the stream that, after 5 seconds, will "flip" the `switch` calling `complete` on it. `delayBy` concatenates the stream after another that sleeps for the specified duration, effectively delaying the evaluation of our stream.
```scala
val program =
  Stream.repeatEval(IO(println(java.time.LocalTime.now))).metered(1.second)
```

This is the program we want to interrupt. `repeatEval` is the effectful version of `repeat`. `metered`, on the other hand, forces our stream to emit values at the specified rate (in this case one every second).

```scala
program
  .interruptWhen(switch.get.attempt)
  .concurrently(switcher)
```

In this line we call `interruptWhen` on the stream, obtaining a stream that will stop evaluation as soon as "the `switch` gets flipped"; then, thanks to `concurrently`, we tell that we want the `switcher` to run in the _background_ ignoring his output. This gives us back the program we described back at the start of this chapter.

This is a way to create a program that runs for a given time, in this example 5 seconds. Timed interruption is such a common use case that FS2 defines the `interruptAfter` method. Armed with this knowledge we can rewrite our example as:
```scala
val program1 =
  Stream.
    repeatEval(IO(println(java.time.LocalTime.now))).
    metered(1.second).
    interruptAfter(5.seconds)
// program1: Stream[[x]IO[x], Unit] = Stream(..)

program1.compile.drain.unsafeRunSync()
// 17:14:16.652251290
// 17:14:17.653019243
// 17:14:18.652753594
// 17:14:19.652488548
```

### Talking to the external world

When talking to the external world, there are a few different situations you might encounter:

* [Functions which execute side effects _synchronously_](#synchronous-effects). These are the easiest to deal with.
* [Functions which execute effects _asynchronously_, and invoke a callback _once_](#asynchronous-effects-callbacks-invoked-once) when completed. Example: fetching 4MB from a file on disk might be a function that accepts a callback to be invoked when the bytes are available.
* [Functions which execute effects asynchronously, and invoke a callback _one or more times_](#asynchronous-effects-callbacks-invoked-multiple-times) as results become available. Example: a database API which asynchronously streams results of a query as they become available.

We'll consider each of these in turn.

#### Synchronous effects

These are easy to deal with. Just wrap these effects in a `Stream.eval`:

```scala
def destroyUniverse(): Unit = { println("BOOOOM!!!"); } // stub implementation

val s = Stream.exec(IO { destroyUniverse() }) ++ Stream("...moving on")
// s: Stream[[x]IO[x], String] = Stream(..)
s.compile.toVector.unsafeRunSync()
// BOOOOM!!!
// res53: Vector[String] = Vector("...moving on")
```

The way you bring synchronous effects into your effect type may differ. `Sync.delay` can be used for this generally, without committing to a particular effect:

```scala
import cats.effect.Sync

val T = Sync[IO]
// T: cats.effect.kernel.Async[IO] = cats.effect.IO$IOAsync@3f3b6fe8
val s2 = Stream.exec(T.delay { destroyUniverse() }) ++ Stream("...moving on")
// s2: Stream[[x]IO[x], String] = Stream(..)
s2.compile.toVector.unsafeRunSync()
// BOOOOM!!!
// res54: Vector[String] = Vector("...moving on")
```

When using this approach, be sure the expression you pass to delay doesn't throw exceptions.

#### Asynchronous effects (callbacks invoked once)

Very often, you'll be dealing with an API like this:

```scala
trait Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit

  // or perhaps
  def readBytesE(onComplete: Either[Throwable,Array[Byte]] => Unit): Unit =
    readBytes(bs => onComplete(Right(bs)), e => onComplete(Left(e)))

  override def toString = "<connection>"
}
```

That is, we provide a `Connection` with two callbacks (or a single callback that accepts an `Either`), and at some point later, the callback will be invoked _once_. The `cats.effect.Async` trait provides a handy function in these situations:

```scala
trait Async[F[_]] extends Sync[F] with Temporal[F] {
  ...
  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  def async_[A](register: (Either[Throwable,A] => Unit) => Unit): F[A]
}
```

Here's a complete example:

```scala
val c = new Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit = {
    Thread.sleep(200)
    onSuccess(Array(0,1,2))
  }
}
// c: AnyRef with Connection = <connection>

val bytes = IO.async_[Array[Byte]] { cb => c.readBytesE(cb) }
// bytes: IO[Array[Byte]] = IO(...)

Stream.eval(bytes).map(_.toList).compile.toVector.unsafeRunSync()
// res55: Vector[List[Byte]] = Vector(List(0, 1, 2))
```

Be sure to check out the
[`fs2.io`](https://github.com/functional-streams-for-scala/fs2/tree/main/io/)
package which has nice FS2 bindings to Java NIO libraries, using exactly this approach.

#### Asynchronous effects (callbacks invoked multiple times)

The nice thing about callback-y APIs that invoke their callbacks once is that throttling/back-pressure can be handled within FS2 itself. If you don't want more values, just don't read them, and they won't be produced! But sometimes you'll be dealing with a callback-y API which invokes callbacks you provide it _more than once_. Perhaps it's a streaming API of some sort and it invokes your callback whenever new data is available. In these cases, you can use an asynchronous queue to broker between the nice stream processing world of FS2 and the external API, and use whatever ad hoc mechanism that API provides for throttling of the producer.

_Note:_ Some of these APIs don't provide any means of throttling the producer, in which case you either have to accept possibly unbounded memory usage (if the producer and consumer operate at very different rates), or use blocking concurrency primitives like `cats.effect.std.Queue.bounded` or the primitives in `java.util.concurrent`.

Let's look at a complete example:

```scala
import fs2._
import cats.effect.Async
import cats.effect.std.{Dispatcher, Queue}

type Row = List[String]
type RowOrError = Either[Throwable, Row]

trait CSVHandle {
  def withRows(cb: RowOrError => Unit): Unit
}

def rows[F[_]](h: CSVHandle)(implicit F: Async[F]): Stream[F,Row] = {
  for {
    dispatcher <- Stream.resource(Dispatcher.sequential[F])
    q <- Stream.eval(Queue.unbounded[F, Option[RowOrError]])
    _ <- Stream.eval { F.delay {
      def enqueue(v: Option[RowOrError]): Unit = dispatcher.unsafeRunAndForget(q.offer(v))

      // Fill the data - withRows blocks while reading the file, asynchronously invoking the callback we pass to it on every row
      h.withRows(e => enqueue(Some(e)))
      // Upon returning from withRows, signal that our stream has ended.
      enqueue(None)
    }}
    // Due to `fromQueueNoneTerminated`, the stream will terminate when it encounters a `None` value
    row <- Stream.fromQueueNoneTerminated(q).rethrow
  } yield row
}
```

See [`Queue`](https://github.com/typelevel/cats-effect/blob/series/3.x/std/shared/src/main/scala/cats/effect/std/Queue.scala)
for more useful methods. Most concurrent queues in cats effect support tracking their size, which is handy for implementing size-based throttling of the producer.

### Reactive streams

The [reactive streams initiative](http://www.reactive-streams.org/) is complicated, mutable and unsafe - it is not something that is desired for use over fs2.
But there are times when we need use fs2 in conjunction with a different streaming library, and this is where reactive streams shines.

Any reactive streams system can interoperate with any other reactive streams system by exposing an `org.reactivestreams.Publisher` or an `org.reactivestreams.Subscriber`.

The `reactive-streams` library provides instances of reactive streams compliant publishers and subscribers to ease interoperability with other streaming libraries.

#### Usage

You may require the following imports:

```scala
import fs2._
import fs2.interop.reactivestreams._
import cats.effect.{IO, Resource}
```

To convert a `Stream` into a downstream unicast `org.reactivestreams.Publisher`:

```scala
val stream = Stream(1, 2, 3).covary[IO]
// stream: Stream[IO, Int] = Stream(..)
stream.toUnicastPublisher
// res58: Resource[[A]IO[A], StreamUnicastPublisher[[A]IO[A], Int]] = Bind(
//   source = Bind(
//     source = Bind(
//       source = Eval(
//         fa = Delay(
//           thunk = cats.effect.IO$$$Lambda$12412/0x00007ff13408af40@6976ff52,
//           event = cats.effect.tracing.TracingEvent$StackTrace
//         )
//       ),
//       fs = cats.effect.std.Supervisor$$$Lambda$13321/0x00007ff134295430@2272be3e
//     ),
//     fs = cats.effect.std.Dispatcher$$$Lambda$13322/0x00007ff134295800@5c3476e6
//   ),
//   fs = cats.effect.kernel.Resource$$Lambda$13264/0x00007ff1342755d0@24bec621
// )
```

To convert an upstream `org.reactivestreams.Publisher` into a `Stream`:

```scala
val publisher: Resource[IO, StreamUnicastPublisher[IO, Int]] = Stream(1, 2, 3).covary[IO].toUnicastPublisher
// publisher: Resource[IO, StreamUnicastPublisher[IO, Int]] = Bind(
//   source = Bind(
//     source = Bind(
//       source = Eval(
//         fa = Delay(
//           thunk = cats.effect.IO$$$Lambda$12412/0x00007ff13408af40@7a6c318b,
//           event = cats.effect.tracing.TracingEvent$StackTrace
//         )
//       ),
//       fs = cats.effect.std.Supervisor$$$Lambda$13321/0x00007ff134295430@4d58e3b6
//     ),
//     fs = cats.effect.std.Dispatcher$$$Lambda$13322/0x00007ff134295800@32a42fbd
//   ),
//   fs = cats.effect.kernel.Resource$$Lambda$13264/0x00007ff1342755d0@60796806
// )
publisher.use { p =>
  p.toStream[IO].compile.toList
}
// res59: IO[List[Int]] = FlatMap(
//   ioe = Delay(
//     thunk = cats.effect.IO$$$Lambda$12412/0x00007ff13408af40@7a6c318b,
//     event = cats.effect.tracing.TracingEvent$StackTrace
//   ),
//   f = cats.effect.kernel.Resource$$Lambda$13324/0x00007ff134296a40@3be42607,
//   event = cats.effect.tracing.TracingEvent$StackTrace
// )
```

A unicast publisher must have a single subscriber only.

### Learning more

Want to learn more?

* Worked examples: these present a nontrivial example of use of the library, possibly making use of lots of different library features.
  * [The example](https://fs2.io/#/getstarted/example)
  * More contributions welcome! Open a PR, following the style of one of the examples above. You can either start with a large block of code and break it down line by line, or work up to something more complicated using some smaller bits of code first.
* Detailed coverage of different modules in the library:
  * File I/O
  * TCP networking
  * UDP networking
  * Contributions welcome! If you are familiar with one of the modules of the library and would like to contribute a more detailed guide for it, submit a PR.

Also feel free to come discuss and ask/answer questions in [the Typelevel Discord channel](https://discord.gg/9V8FZTVZ9R) and/or on StackOverflow using [the tag FS2](http://stackoverflow.com/tags/fs2).

### Appendixes

#### Appendix A1: How interruption of streams works

In FS2, a stream can terminate in one of three ways:

1. Normal input exhaustion. For instance, the stream `Stream(1,2,3)` terminates after the single chunk (containing the values `1, 2, 3`) is emitted.
2. An uncaught exception. For instance, the stream `Stream(1,2,3) ++ (throw Err)` terminates with `Err` after the single chunk is emitted.
3. Interruption by the stream consumer. Interruption can be _synchronous_, as in `(Stream(1) ++ (throw Err)) take 1`, which will deterministically halt the stream before the `++`, or it can be _asynchronous_, as in `s1 merge s2 take 3`.

Regarding 3:

* A stream will never be interrupted while it is acquiring a resource (via `bracket`) or while it is releasing a resource. The `bracket` function guarantees that if FS2 starts acquiring the resource, the corresponding release action will be run.
* Other than that, Streams can be interrupted in between any two 'steps' of the stream. The steps themselves are atomic from the perspective of FS2. `Stream.eval(eff)` is a single step, `Stream.emit(1)` is a single step, `Stream(1,2,3)` is a single step (emitting a chunk), and all other operations (like `handleErrorWith`, `++`, and `flatMap`) are multiple steps and can be interrupted.
* _Always use `bracket` or a `bracket`-based function like `onFinalize` for supplying resource cleanup logic or any other logic you want to be run regardless of how the stream terminates. Don't use `handleErrorWith` or `++` for this purpose._

Let's look at some examples of how this plays out, starting with the synchronous interruption case:

```scala
import fs2._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

case object Err extends Throwable

(Stream(1) ++ Stream(2).map(_ => throw Err)).take(1).toList
// res61: List[Int] = List(1)
(Stream(1) ++ Stream.raiseError[IO](Err)).take(1).compile.toList.unsafeRunSync()
// res62: List[Int] = List(1)
```

The `take 1` uses `Pull` but doesn't examine the entire stream, and neither of these examples will ever throw an error. This makes sense. A bit more subtle is that this code will _also_ never throw an error:

```scala
(Stream(1) ++ Stream.raiseError[IO](Err)).take(1).compile.toList.unsafeRunSync()
// res63: List[Int] = List(1)
```

The reason is simple: the consumer (the `take(1)`) terminates as soon as it has an element. Once it has that element, it is done consuming the stream and doesn't bother running any further steps of it, so the stream never actually completes normally---it has been interrupted before that can occur. We may be able to see in this case that nothing follows the emitted `1`, but FS2 doesn't know this until it actually runs another step of the stream.

If instead we use `onFinalize`, the code is guaranteed to run, regardless of whether `take` interrupts:

```scala
Stream(1).covary[IO].
          onFinalize(IO { println("finalized!") }).
          take(1).
          compile.toVector.unsafeRunSync()
// finalized!
// res64: Vector[Int] = Vector(1)
```

That covers synchronous interrupts. Let's look at asynchronous interrupts. Ponder what the result of `merged` will be in this example:

```scala
val s1 = (Stream(1) ++ Stream(2)).covary[IO]
// s1: Stream[IO, Int] = Stream(..)
val s2 = (Stream.empty ++ Stream.raiseError[IO](Err)).handleErrorWith { e => println(e); Stream.raiseError[IO](e) }
// s2: Stream[[x]IO[x], Nothing] = Stream(..)
val merged = s1 merge s2 take 1
// merged: Stream[[x]IO[x], Int] = Stream(..)
```

The result is highly nondeterministic. Here are a few ways it can play out:

* `s1` may complete before the error in `s2` is encountered, in which case nothing will be printed and no error will occur.
* `s2` may encounter the error before any of `s1` is emitted. When the error is reraised by `s2`, that will terminate the `merge` and asynchronously interrupt `s1`, and the `take` terminates with that same error.
* `s2` may encounter the error before any of `s1` is emitted, but during the period where the value is caught by `handleErrorWith`, `s1` may emit a value and the `take(1)` may terminate, triggering interruption of both `s1` and `s2`, before the error is reraised but after the exception is printed! In this case, the stream will still terminate without error.

The correctness of your program should not depend on how different streams interleave, and once again, you should not use `handleErrorWith` or other interruptible functions for resource cleanup. Use `bracket` or `onFinalize` for this purpose.
