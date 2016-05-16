<!--
This markdown file contains code examples which can be compiled using tut. Switch to `project docs`, then do `tut`. Output is produced in `docs/`.
-->

# FS2: The Official Guide

This is the offical FS2 guide. It gives an overview of the library and its features and it's kept up to date with the code. If you spot a problem with this guide, a nonworking example, or simply have some suggested improvments, open a pull request! It's very much a WIP.

_Unless otherwise noted, the type `Stream` mentioned in this document refers to the type `fs2.Stream` and NOT `scala.collection.immutable.Stream`._

The FS2 library has two major capabilites:

* The ability to _build_ arbitrarily complex streams, possibly with embedded effects.
* The ability to _transform_ one or more streams using a small but powerful set of operations

We'll consider each of these in turn.

## Building streams

A `Stream[F,O]` (formerly `Process`) represents a discrete stream of `O` values which may request evaluation of `F` effects. We'll call `F` the _effect type_ and `O` the _output type_. Let's look at some examples:

```scala
scala> import fs2.Stream
import fs2.Stream

scala> val s1 = Stream.emit(1)
s1: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@69927ca1

scala> val s1a = Stream(1,2,3) // variadic
s1a: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@2f2ad9e4

scala> val s1b = Stream.emits(List(1,2,3)) // accepts any Seq
s1b: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@123bbe58
```

The `s1` stream has the type `Stream[Nothing,Int]`. Its effect type is `Nothing`, which means it does not require evaluation of any effects to produce its output. You can convert a pure stream to a `List` or `Vector` using:

```scala
scala> s1.toList
res0: List[Int] = List(1)

scala> s1.toVector
res1: Vector[Int] = Vector(1)
```

Streams have lots of handy 'list-like' functions, here's a very small sample:

```scala
scala> Stream(1,2,3).map(_ + 1).toList
res2: List[Int] = List(2, 3, 4)

scala> Stream(1,2,3).filter(_ % 2 != 0).toList
res3: List[Int] = List(1, 3)

scala> Stream(1,2,3).fold(0)(_ + _).toList
res4: List[Int] = List(6)

scala> Stream(None,Some(2),Some(3)).collect { case Some(i) => i }.toList
res5: List[Int] = List(2, 3)

scala> Stream.range(0,5).intersperse(42).toList
res6: List[Int] = List(0, 42, 1, 42, 2, 42, 3, 42, 4)

scala> Stream(1,2,3).flatMap(i => Stream(i,i)).toList
res7: List[Int] = List(1, 1, 2, 2, 3, 3)
```

Of these, only `flatMap` is primitive, the rest are built using combinations of various other primitives. We'll take a look at how that works shortly.

FS2 streams are chunked internally for performance. You can construct an individual stream chunk using `Stream.chunk`, which accepts an `fs2.Chunk` and lots of functions in the library are chunk-aware and/or try to preserve 'chunkiness' when possible:

```scala
scala> import fs2.Chunk
import fs2.Chunk

scala> val s1c = Stream.chunk(Chunk.doubles(Array(1.0, 2.0, 3.0)))
s1c: fs2.Stream[Nothing,Double] = fs2.Stream$$anon$1@592d1358

scala> s1c.mapChunks {
     |   case ds : Chunk.Doubles => /* do things unboxed */ ds
     |   case ds => ds.map(_ + 1)
     | }
res8: fs2.Stream[Nothing,Double] = fs2.Stream$$anon$1@6c3f16bc
```

_Note:_ The `mapChunks` function is another library primitive. It's used to implement `map` and `filter`.

Let's look at some other example streams:

```scala
scala> val s2 = Stream.empty
s2: fs2.Stream[Nothing,Nothing] = fs2.Stream$$anon$1@f4ff0fa

scala> s2.toList
res9: List[Nothing] = List()

scala> import fs2.util.Task
import fs2.util.Task

scala> val eff = Stream.eval(Task.delay { println("TASK BEING RUN!!"); 1 + 1 })
eff: fs2.Stream[fs2.util.Task,Int] = fs2.Stream$$anon$1@50aa6536
```

`Task` is an effect type we'll see a lot in these examples. Creating a `Task` has no side effects; nothing is actually run at the time of creation. Notice the type of `eff` is now `Stream[Task,Int]`.

The `eval` function works for any effect type, not just `Task`:

```Scala
def eval[F[_],A](f: F[A]): Stream[F,A]
```

_Note_: FS2 does not care what effect type you use for your streams. You may use the included [`Task` type][Task] for effects or bring your own, just by implementing a few interfaces for your effect type. ([`Catchable`][Catchable] and optionally [`Async`][Async] if you wish to use various concurrent operations discussed later.)

[Task]: ../core/src/main/scala/fs2/util/Task.scala
[Catchable]: ../core/src/main/scala/fs2/util/Catchable.scala
[Async]: ../core/src/main/scala/fs2/Async.scala

It produces a stream that evaluates the given effect, then emits the result (notice that `F` is unconstrained) Any `Stream` formed using `eval` is called 'effectful' and can't be run using `toList` or `toVector`. If we try we'll get a compile error:

```scala
scala> eff.toList
<console>:17: error: value toList is not a member of fs2.Stream[fs2.util.Task,Int]
       eff.toList
           ^
```

To run an effectful stream, use one of the `run` methods on `Stream`, for instance:

```scala
scala> val ra = eff.runLog
ra: fs2.util.Free[fs2.util.Task,Vector[Int]] = Bind(Bind(Pure(()),<function1>),<function1>)

scala> val rb = eff.run // purely for effects
rb: fs2.util.Free[fs2.util.Task,Unit] = Bind(Bind(Pure(()),<function1>),<function1>)

scala> val rc = eff.runFold(0)(_ + _) // run and accumulate some result
rc: fs2.util.Free[fs2.util.Task,Int] = Bind(Bind(Pure(()),<function1>),<function1>)
```

Notice these all return an `fs2.util.Free[Task,_]`. `Free` has various useful functions on it (for instance, we can translate to a different effect type), but most of the time we'll just want to run it using the `run` method:

```scala
scala> ra.run
res11: fs2.util.Task[Vector[Int]] = fs2.util.Task@4595f34d

scala> rb.run
res12: fs2.util.Task[Unit] = fs2.util.Task@38de7684

scala> rc.run
res13: fs2.util.Task[Int] = fs2.util.Task@124d3c5b
```

This requires an implicit `Catchable[Task]` in scope (or a `Catchable[F]` for whatever your effect type, `F`).

Notice nothing has actually happened yet (our `println` hasn't been executed), we just have a `Task`. If we want to run this for its effects 'at the end of the universe', we can use one of the `unsafe*` methods on `Task`:

```scala
scala> ra.run.unsafeRun
TASK BEING RUN!!
res14: Vector[Int] = Vector(2)

scala> ra.run.unsafeRun
TASK BEING RUN!!
res15: Vector[Int] = Vector(2)
```

Here we finally see the task is executed. Rerunning the task executes the entire computation again; nothing is cached for you automatically. Here's a complete example:

```scala
scala> Stream.eval(Task.now(23)).runLog.run.unsafeRun
res16: Vector[Int] = Vector(23)
```

### Stream operations

Streams have a small but powerful set of operations. The key operations are `++`, `map`, `flatMap`, `onError`, and `bracket`:

```scala
scala> val appendEx1 = Stream(1,2,3) ++ Stream.emit(42)
appendEx1: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@f489e1f

scala> val appendEx2 = Stream(1,2,3) ++ Stream.eval(Task.now(4))
appendEx2: fs2.Stream[fs2.util.Task,Int] = fs2.Stream$$anon$1@494f3935

scala> appendEx1.toVector
res17: Vector[Int] = Vector(1, 2, 3, 42)

scala> appendEx2.runLog.run.unsafeRun
res18: Vector[Int] = Vector(1, 2, 3, 4)

scala> appendEx1.map(_ + 1).toList
res19: List[Int] = List(2, 3, 4, 43)
```

The `flatMap` operation is the same idea as lists - it maps, then concatenates:

```scala
scala> appendEx1.flatMap(i => Stream.emits(List(i,i))).toList
res20: List[Int] = List(1, 1, 2, 2, 3, 3, 42, 42)
```

Regardless of how a `Stream` is built up, each operation takes constant time. So `p ++ p2` takes constant time, regardless of whether `p` is `Stream.emit(1)` or it's a huge stream with millions of elements and lots of embedded effects. Likewise with `p.flatMap(f)` and `onError`, which we'll see in a minute. The runtime of these operations do not depend on the structure of `p`.

#### Error handling

A stream can raise errors, either explicitly, using `Stream.fail`, or implicitly via an exception in pure code or inside an effect passed to `eval`:

```scala
scala> val err = Stream.fail(new Exception("oh noes!"))
err: fs2.Stream[Nothing,Nothing] = fs2.Stream$$anon$1@1c9002af

scala> val err2 = Stream(1,2,3) ++ (throw new Exception("!@#$"))
err2: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@68c350ae

scala> val err3 = Stream.eval(Task.delay(throw new Exception("error in effect!!!")))
err3: fs2.Stream[fs2.util.Task,Nothing] = fs2.Stream$$anon$1@62916edd
```

All these fail when running:

```scala
scala> err.toList
java.lang.Exception: oh noes!
  ... 750 elided
```

```scala
scala> err2.toList
java.lang.Exception: !@#$
  at $anonfun$1.apply(<console>:15)
  at $anonfun$1.apply(<console>:15)
  at fs2.Stream$$anonfun$append$1.apply(Stream.scala:85)
  at fs2.Stream$$anonfun$append$1.apply(Stream.scala:85)
  at fs2.StreamCore$$anonfun$suspend$1.apply(StreamCore.scala:365)
  at fs2.StreamCore$$anonfun$suspend$1.apply(StreamCore.scala:365)
  at fs2.StreamCore$$anonfun$stepTrace$2$$anon$5$$anonfun$f$1.liftedTree2$1(StreamCore.scala:280)
  at fs2.StreamCore$$anonfun$stepTrace$2$$anon$5$$anonfun$f$1.apply(StreamCore.scala:280)
  at fs2.StreamCore$$anonfun$stepTrace$2$$anon$5$$anonfun$f$1.apply(StreamCore.scala:265)
  at fs2.StreamCore$Stack$$anon$13$$anon$14$$anonfun$f$3.apply(StreamCore.scala:445)
  at fs2.StreamCore$Stack$$anon$13$$anon$14$$anonfun$f$3.apply(StreamCore.scala:444)
  at fs2.StreamCore$Stack$$anon$11.apply(StreamCore.scala:423)
  at fs2.StreamCore$Stack$$anon$13.apply(StreamCore.scala:441)
  at fs2.StreamCore$$anonfun$stepTrace$2.apply(StreamCore.scala:251)
  at fs2.StreamCore$$anonfun$stepTrace$2.apply(StreamCore.scala:245)
  at scala.Function1$$anonfun$andThen$1.apply(Function1.scala:52)
  at fs2.util.Free$$anonfun$_step$1.apply(Free.scala:59)
  at fs2.util.Free$$anonfun$_step$1.apply(Free.scala:59)
  at fs2.util.Free$class._step(Free.scala:62)
  at fs2.util.Free$Bind._step(Free.scala:129)
  at fs2.util.Free$class.step(Free.scala:55)
  at fs2.util.Free$Bind.step(Free.scala:129)
  at fs2.util.Free$class.fold(Free.scala:13)
  at fs2.util.Free$Bind.fold(Free.scala:129)
  at fs2.util.Free$Bind$$anonfun$_fold$2$$anonfun$apply$6.apply(Free.scala:159)
  at fs2.util.Free$Bind$$anonfun$_fold$2$$anonfun$apply$6.apply(Free.scala:159)
  at scala.Function1$$anonfun$andThen$1.apply(Function1.scala:52)
  at fs2.Scope$$anonfun$bindEnv$1$$anon$2$$anonfun$f$1.apply(Scope.scala:33)
  at fs2.Scope$$anonfun$bindEnv$1$$anon$2$$anonfun$f$1.apply(Scope.scala:29)
  at fs2.util.Free$Bind$$anonfun$_fold$2.apply(Free.scala:157)
  at fs2.util.Free$$anonfun$suspend$1.apply(Free.scala:94)
  at fs2.util.Free$$anonfun$suspend$1.apply(Free.scala:94)
  at fs2.util.Free$$anonfun$_step$1.apply(Free.scala:59)
  at fs2.util.Free$$anonfun$_step$1.apply(Free.scala:59)
  at fs2.util.Free$class._step(Free.scala:62)
  at fs2.util.Free$Bind._step(Free.scala:129)
  at fs2.util.Free$class.step(Free.scala:55)
  at fs2.util.Free$Bind.step(Free.scala:129)
  at fs2.util.Free$class.runTranslate(Free.scala:41)
  at fs2.util.Free$Bind.runTranslate(Free.scala:129)
  at fs2.util.Free$class.run(Free.scala:53)
  at fs2.util.Free$Bind.run(Free.scala:129)
  at fs2.StreamDerived$StreamPureOps.toList(StreamDerived.scala:218)
  ... 798 elided
```

```scala
scala> err3.runLog.run.unsafeRun
java.lang.Exception: error in effect!!!
  at $anonfun$1.apply(<console>:15)
  at $anonfun$1.apply(<console>:15)
  at fs2.util.Task$$anonfun$delay$1.apply(Task.scala:204)
  at fs2.util.Task$$anonfun$delay$1.apply(Task.scala:204)
  at fs2.util.Task$$anonfun$suspend$1$$anonfun$2.apply(Task.scala:212)
  at fs2.util.Task$$anonfun$suspend$1$$anonfun$2.apply(Task.scala:212)
  at fs2.util.Task$.Try(Task.scala:276)
  at fs2.util.Task$$anonfun$suspend$1.apply(Task.scala:212)
  at fs2.util.Task$$anonfun$suspend$1.apply(Task.scala:212)
  at fs2.internal.Future.step(Future.scala:53)
  at fs2.internal.Future.listen(Future.scala:29)
  at fs2.internal.Future.runAsync(Future.scala:68)
  at fs2.internal.Future.run(Future.scala:78)
  at fs2.util.Task.unsafeRun(Task.scala:108)
  ... 814 elided
```

The `onError` method lets us catch any of these errors:

```scala
scala> err.onError { e => Stream.emit(e.getMessage) }.toList
res24: List[String] = List(oh noes!)
```

#### Resource acquisition

If you have to acquire a resource and want to guarantee that some cleanup action is run, use the `bracket` function:

```scala
scala> val count = new java.util.concurrent.atomic.AtomicLong(0)
count: java.util.concurrent.atomic.AtomicLong = 0

scala> val acquire = Task.delay { println("incremented: " + count.incrementAndGet); () }
acquire: fs2.util.Task[Unit] = fs2.util.Task@6598df50

scala> val release = Task.delay { println("decremented: " + count.decrementAndGet); () }
release: fs2.util.Task[Unit] = fs2.util.Task@301973c4
```

```scala
scala> Stream.bracket(acquire)(_ => Stream(1,2,3) ++ err, _ => release).run.run.unsafeRun
incremented: 1
decremented: 0
java.lang.Exception: oh noes!
  ... 750 elided
```

The inner stream fails, but notice the `release` action is still run:

```scala
scala> count.get
res26: Long = 0
```

No matter how you transform an FS2 `Stream` or where any errors occur, the library guarantees that if the resource is acquired via a `bracket`, the release action associated with that `bracket` will be run. Here's the signature of `bracket`:

```Scala
def bracket[F[_],R,O](acquire: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,O]
```

FS2 guarantees _once and only once_ semantics for resource cleanup actions introduced by the `Stream.bracket` function.

For the full set of operations primitive operations on `Stream`, see the [`Streams` trait](../core/src/main/scala/fs2/Streams.scala), which the [`Stream` companion object](../core/src/main/scala/fs2/Stream.scala) implements. There are only 11 primitive operations, and we've already seen most of them above! Note that for clarity, the primitives in `Streams` are defined in a `trait` as standalone functions, but for convenience these same functions are exposed with infix syntax on the `Stream` type. So `Stream.onError(s)(h)` may be invoked as `s.onError(h)`, and so on.

## Statefully transforming streams

We often wish to statefully transform one or more streams in some way, possibly evaluating effects as we do so. As a running example, consider taking just the first 5 elements of a `s: Stream[Task,Int]`. To produce a `Stream[Task,Int]` which takes just the first 5 elements of `s`, we need to repeadedly await (or pull) values from `s`, keeping track of the number of values seen so far and stopping as soon as we hit 5 elements. In more complex scenarios, we may want to evaluate additional effects as we pull from one or more streams.

Regardless of how complex the job, the `fs2.Pull` and `fs2.Stream.Handle` types can usually express it. `Handle[F,I]` represents a 'currently open' `Stream[F,I]`. We obtain one using `Stream.open`, or the method on `Stream`, `s.open`, which returns the `Handle` inside an effect type called `Pull`:

```Scala
// in fs2.Stream object
def open[F[_],I](s: Stream[F,I]): Pull[F,Nothing,Handle[F,I]]
```

The `trait Pull[+F[_],+O,+R]` represents a program that may pull values from one or more `Handle` values, write _output_ of type `O`, and return a _result_ of type `R`. It forms a monad in `R` and comes equipped with lots of other useful operations. See the [`Pulls` trait](../core/src/main/scala/fs2/Pulls.scala) for the full set of primitive operations on `Pull`.

Let's look at the core operation for implementing `take`. It's just a recursive function:

```scala
import fs2._
// import fs2._

import fs2.Stream.Handle
// import fs2.Stream.Handle

import fs2.Step._ // provides '#:' constructor, also called Step
// import fs2.Step._

object Pull_ {

  def take[F[_],O](n: Int)(h: Handle[F,O]): Pull[F,O,Nothing] =
    for {
      chunk #: h <- if (n <= 0) Pull.done else Pull.awaitLimit(n)(h)
      tl <- Pull.output(chunk) >> take(n - chunk.size)(h)
    } yield tl
}
// defined object Pull_

Stream(1,2,3,4).pure.pull(Pull_.take(2)).toList
// res27: List[Int] = List(1, 2)
```

Let's break it down line by line:

```Scala
chunk #: h <- if (n <= 0) Pull.done else Pull.awaitLimit(n)(h)
```

There's a lot going on in this one line:

* If `n <= 0`, we're done, and stop pulling.
* Otherwise we have more values to `take`, so we `Pull.awaitLimit(n)(h)`, which returns a `Step[Chunk[A],Handle[F,I]]` (again, inside of the `Pull` effect).
* The `Pull.awaitLimit(n)(h)` reads from the handle but gives us a `Chunk[O]` with _no more than_ `n` elements. (We can also `h.await1` to read just a single element, `h.await` to read a single `Chunk` of however many are available, `Pull.awaitN(n)(h)` to obtain a `List[Chunk[A]]` totaling exactly `n` elements, and even `h.awaitAsync` and various other _asynchronous_ awaiting functions which we'll discuss in the next section.)
* Using the pattern `chunk #: h` (defined in `fs2.Step`), we destructure this `Step` to its `chunk: Chunk[O]` and its `h: Handle[F,O]`. This shadows the outer `h`, which is fine here since it isn't relevant anymore. (Note: nothing stops us from keeping the old `h` around and awaiting from it again if we like, though this isn't usually what we want since it will repeat all the effects of that await.)

Moving on, the `Pull.output(chunk)` writes the chunk we just read to the _output_ of the `Pull`. This binds the `O` type in our `Pull[F,O,R]` we are constructing:

```Scala
// in fs2.Pull object
def output[O](c: Chunk[O]): Pull[Nothing,O,Unit]
```

It returns a result of `Unit`, which we generally don't care about. The `p >> p2` operator is equivalent to `p flatMap { _ => p2 }`; it just runs `p` for its effects but ignores its result.

So this line is writing the chunk we read, ignoring the `Unit` result, then recusively calling `take` with the new `Handle`, `h`:

```Scala
      ...
      tl <- Pull.output(chunk) >> take(n - chunk.size)(h)
    } yield tl
```

For the recursive call, we update the state, subtracting the `chunk.size` elements we've seen. Easy!

To actually use a `Pull` to transform a `Stream`, we have to `run` it:

```scala
scala> val s2 = Stream(1,2,3,4).pure.pull(Pull_.take(2))
s2: fs2.Stream[fs2.Pure,Int] = fs2.Stream$$anon$1@58dc1530

scala> s2.toList
res28: List[Int] = List(1, 2)

scala> val s3 = Stream.pure(1,2,3,4).pull(Pull_.take(2)) // alternately
s3: fs2.Stream[fs2.Pure,Int] = fs2.Stream$$anon$1@360862ac

scala> s3.toList
res29: List[Int] = List(1, 2)
```

_Note:_ The `.pure` converts a `Stream[Nothing,A]` to a `Stream[Pure,A]`. Scala will not infer `Nothing` for a type parameter, so using `Pure` as the effect provides better type inference in some cases.

The `pull` method on `Stream` just calls `open` then `run`. We could express the above as:

```scala
scala> Stream(1,2,3,4).pure.open.flatMap { Pull_.take(2) }.run
res30: fs2.Stream[[x]fs2.Pure[x],Int] = fs2.Stream$$anon$1@7a4c7a7e
```

FS2 takes care to guarantee that any resources allocated by the `Pull` are released when the `run` completes. Note again that _nothing happens_ when we call `.run` on a `Pull`, it is merely establishing a scope in which all resource allocations are tracked so that they may be appropriately freed.

There are lots of useful transformation functions in [`pipe`](../core/src/scala/main/fs2/pipe) and [`pipe2`](../core/src/main/fs2/pipe2) built using the `Pull` type, for example:

```scala
import fs2.{pipe, pipe2}
// import fs2.{pipe, pipe2}

val s = Stream.pure(1,2,3,4,5) // alternately Stream(...).pure
// s: fs2.Stream[fs2.Pure,Int] = fs2.Stream$$anon$1@661a069c

// all equivalent
pipe.take(2)(s).toList
// res32: List[Int] = List(1, 2)

s.through(pipe.take(2)).toList
// res33: List[Int] = List(1, 2)

s.take(2).toList
// res34: List[Int] = List(1, 2)

val ns = Stream.range(10,100,by=10)
// ns: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@2e60e553

// all equivalent
s.through2(ns)(pipe2.zip).toList
// res36: List[(Int, Int)] = List((1,10), (2,20), (3,30), (4,40), (5,50))

pipe2.zip(s, ns).toList
// res37: List[(Int, Int)] = List((1,10), (2,20), (3,30), (4,40), (5,50))

s.zip(ns).toList
// res38: List[(Int, Int)] = List((1,10), (2,20), (3,30), (4,40), (5,50))
```

#### Exercises

Try implementing `takeWhile`, `intersperse`, and `scan`:

```scala
scala> Stream.range(0,100).takeWhile(_ < 7).toList
res39: List[Int] = List(0, 1, 2, 3, 4, 5, 6)

scala> Stream("Alice","Bob","Carol").intersperse("|").toList
res40: List[String] = List(Alice, |, Bob, |, Carol)

scala> Stream.range(1,10).scan(0)(_ + _).toList // running sum
res41: List[Int] = List(0, 1, 3, 6, 10, 15, 21, 28, 36, 45)
```

### Concurrency

FS2 comes with lots of concurrent operations. The `merge` function runs two streams concurrently, combining their outputs. It halts when both inputs have halted:

```scala
scala> Stream(1,2,3).merge(Stream.eval(Task.delay { Thread.sleep(200); 4 })).runLog.run.unsafeRun
<console>:24: error: No implicit `Async[fs2.util.Task]` found.
Note that the implicit `Async[fs2.util.Task]` requires an implicit `fs2.util.Strategy` in scope.
       Stream(1,2,3).merge(Stream.eval(Task.delay { Thread.sleep(200); 4 })).runLog.run.unsafeRun
                          ^
```

Oop, we need an `fs2.Strategy` in implicit scope in order to get an `Async[Task]`. Let's add that:

```scala
scala> implicit val S = fs2.Strategy.fromFixedDaemonPool(8, threadName = "worker")
S: fs2.Strategy = fs2.Strategy$$anon$5@7e321b9b

scala> Stream(1,2,3).merge(Stream.eval(Task.delay { Thread.sleep(200); 4 })).runLog.run.unsafeRun
res43: Vector[Int] = Vector(1, 2, 3, 4)
```

The `merge` function is defined in [`pipe2`](../core/src/main/scala/fs2/pipe2), along with other useful concurrency functions, like `interrupt` (halts if the left branch produces `false`), `either` (like `merge` but returns an `Either`), `mergeHaltBoth` (halts if either branch halts), and others.

The function `concurrent.join` runs multiple streams concurrently. The signature is:

```Scala
// note Async[F] bound
def join[F[_]:Async,O](maxOpen: Int)(outer: Stream[F,Stream[F,O]]): Stream[F,O]
```

It flattens the nested stream, letting up to `maxOpen` inner streams run at a time. `s merge s2` could be implemented as `concurrent.join(2)(Stream(s,s2))`.

The `Async` bound on `F` is required anywhere concurrency is used in the library. As mentioned earlier, though FS2 provides the [`fs2.util.Task`][Task] type for convenience, and `Task` has an `Async`, users can bring their own effect types provided they also supply an `Async` instance.

If you examine the implementations of the above functions, you'll see a few primitive functions used. Let's look at those. First, `Stream.awaitAsync` requests the next step of a `Handle` asynchronously. Its signature is:

```Scala
type AsyncStep[F[_],A] = Async.Future[F, Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]]

def awaitAsync[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep[F,A]]
```

A `Future[F,A]` represents a running computation that will eventually yield an `A`. A `Future[F,A]` has a method `.force`, of type `Pull[F,Nothing,A]` that can be used to block until the result is available. A `Future[F,A]` may be raced with another `Future` also---see the implementation of [`pipe2.merge`](../core/src/main/scala/fs2/pipe2).

In addition, there are a number of other concurrency primitives---asynchronous queues, signals, and semaphores. See the [`async` package object](../core/src/main/scala/fs2/async/async.scala) for more details. We'll make use of some of these in the next section when discussing how to talk to the external world.

#### Exercises

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

## Talking to the external world

When talking to the external world, there are a few different situations you might encounter.

* Functions which execute side effect _synchronously_. These are the easiest to deal with.
* Functions which execute effects _asynchronously_, and invoke a callback _once_ when completed. Example: fetching 4MB from disk might be a function that accepts a callback to be invoked when the bytes are available.
* Functions which execute effects asynchronously, and invoke a callback _one or more times_ as results become available. Example: a database API which asynchronously streams results of a query as they become available.

We'll consider each of these in turn.

#### Synchronous effects

These are easy to deal with. Just wrap these effects in a `Stream.eval`:

```scala
def destroyUniverse(): Unit = { println("BOOOOM!!!"); } // stub implementation
// destroyUniverse: ()Unit

val s = Stream.eval_(Task.delay { destroyUniverse() }) ++ Stream("...moving on")
// s: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@15b6f8

s.runLog.run.unsafeRun
// BOOOOM!!!
// res44: Vector[String] = Vector(...moving on)
```

_Note:_ `Stream.eval_(f)` is just `Stream.eval(f).flatMap(_ => Stream.empty)`.

The way you bring synchronous effects into your effect type may differ. [`Async.suspend`](../core/src/main/scala/fs2/Async.scala) can be used for this generally, without committing to a particular effect:

```scala
val T = implicitly[Async[Task]]
// T: fs2.Async[fs2.util.Task] = fs2.util.Instances$$anon$1@401a8c2c

val s = Stream.eval_(T.suspend { destroyUniverse() }) ++ Stream("...moving on")
// s: fs2.Stream[fs2.util.Task,String] = fs2.Stream$$anon$1@37d8823c

s.runLog.run.unsafeRun
// BOOOOM!!!
// res45: Vector[String] = Vector(...moving on)
```

Just be sure when using this approach to catch and return as values any exceptions that could be thrown by the computation when it is forced.

#### Asynchronous effects (callbacks invoked once)

Very often, you'll be dealing with an API like this:

```scala
trait Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit

  // or perhaps
  def readBytesE(onComplete: Either[Throwable,Array[Byte]] => Unit): Unit =
    readBytes(bs => onComplete(Right(bs)), e => onComplete(Left(e)))
}
// defined trait Connection
```

That is, we provide a `Connection` with two callbacks (or a single callback that accepts an `Either`), and at some point later, they'll be invoked _once_. The `Async` trait provides a handy function in these situations:

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

```scala
val c = new Connection {
  def readBytes(onSuccess: Array[Byte] => Unit, onFailure: Throwable => Unit): Unit = {
    Thread.sleep(200)
    onSuccess(Array(0,1,2))
  }
}
// c: Connection = $anon$1@1fed2e03

// recall T: Async[Task]
val bytes = T.async[Array[Byte]] { (cb: Either[Throwable,Array[Byte]] => Unit) =>
  T.suspend { c.readBytesE(cb) }
}
// bytes: fs2.util.Task[Array[Byte]] = fs2.util.Task@44be94a1

Stream.eval(bytes).map(_.toList).runLog.run.unsafeRun
// res47: Vector[List[Byte]] = Vector(List(0, 1, 2))
```

Be sure to check out the [`fs2.io`](../io) package which has nice FS2 bindings to these libraries, using exactly these approaches.

#### Asynchronous effects (callbacks invoked multiple times)

The nice thing about callback-y APIs that invoke their callbacks once is that throttling/pushback can be handled within FS2 itself. If you don't want more values, just don't read them, and they won't be produced! But sometimes you'll be dealing with a callback-y API which invokes callbacks you provide it _more than once_. Perhaps it's a streaming API of some sort and it invokes your callback whenever new data is available. In these cases, you can use an asynchronous queue to broker between the nice stream processing world of FS2 and the external API, and use whatever ad hoc mechanism that API provides for throttling of the producer.

_Note:_ Some of these APIs don't provide any means of throttling the producer, in which case you either have accept possibly unbounded memory usage (if the producer and consumer operate at very different rates), or use blocking concurrency primitives like those provided by `java.util.concurrent`.

Let's look at a complete example:

```scala
import fs2.Async.Run
// import fs2.Async.Run

type Row = List[String]
// defined type alias Row

trait CSVHandle {
  def withRows(cb: Either[Throwable,Row] => Unit): Unit
}
// defined trait CSVHandle

def rows[F[_]](h: CSVHandle)(implicit F: Async[F], R: Run[F]): Stream[F,Row] =
  for {
    q <- Stream.eval(async.unboundedQueue[F,Either[Throwable,Row]])
    _ <- Stream.suspend { h.withRows { e => R.runEffects(q.enqueue1(e)); () }; Stream.emit(()) }
    row <- q.dequeue through pipe.rethrow
  } yield row
// rows: [F[_]](h: CSVHandle)(implicit F: fs2.Async[F], implicit R: fs2.Async.Run[F])fs2.Stream[F,Row]
```

See [`Queue`](../core/src/main/scala/fs2/async/mutable/Queue) for more useful methods. All asynchronous queues in FS2 track their size, which is handy for implementing size-based throttling of the producer.

### Appendix A1: Sane subtyping with better error messages

`Stream[F,O]` and `Pull[F,O,R]` are covariant in `F`, `O`, and `R`. This is important for usability and convenience, but covariance can often paper over what should really be type errors. Luckily, FS2 implements a trick to catch these situations. For instance:

```scala
scala> Stream.emit(1) ++ Stream.emit("hello")
<console>:26: error: Dubious upper bound Any inferred for Int; supply `RealSupertype.allow[Int,Any]` here explicitly if this is not due to a type error
       Stream.emit(1) ++ Stream.emit("hello")
                      ^
```

Informative! If you really want a dubious supertype like `Any`, `AnyRef`, `AnyVal`, `Product`, or `Serializable` to be inferred, just follow the instructions in the error message to supply a `RealSupertype` instance explicitly.

```scala
scala> import fs2.util.{RealSupertype, Sub1}
import fs2.util.{RealSupertype, Sub1}

scala> Stream.emit(1).++(Stream("hi"))(RealSupertype.allow[Int,Any], Sub1.sub1[Task])
res49: fs2.Stream[fs2.util.Task,Any] = fs2.Stream$$anon$1@ae76b6a
```

Ugly, as it should be.
