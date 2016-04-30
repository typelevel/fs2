# FS2: The Official Guide

This is the offical FS2 guide. It gives an overview of the library and its features and it's kept up to date with the code. If you spot a problem with this guide, a nonworking example, or simply have some suggested improvments, open a pull request! It's very much a WIP.

_Unless otherwise noted, the type `Stream` mentioned in this document refers to the type `fs2.Stream` and NOT `scala.collection.immutable.Stream`._

The FS2 library has two major capabilites:

* The ability to _build_ arbitrarily complex streams, possibly with embedded effects.
* The ability to _transform_ one or more streams using a small but powerful set of operations

We'll consider each of these in turn.

## Building streams

A `Stream[F,W]` (formerly `Process`) represents a discrete stream of `W` values which may request evaluation of `F` effects. We'll call `F` the _effect type_ and `W` the _output type_. Here are some example streams (these assume you've done `import fs2.Stream` and `import fs2.util.Task`):

* `Stream.emit(1)`: A `Stream[Nothing,Int]`. Its effect type is `Nothing` since it requests evaluation of no effects.
* `Stream.empty`: A `Stream[Nothing,Nothing]` which emits no values. Its output type is `Nothing` since it emits no elements.
* `Stream.fail(err: Throwable)`: A `Stream[Nothing,Nothing]` which emits no values and fails with the error `err`.
* `Stream("hi","there","bob")`. A `Stream[Nothing,String]` which emits three values and has no effects.
* `Stream.eval(t: Task[Int])`. A `Stream[Task,Int]` which requests evaluation of `t` then emits the result of this evaluation. So `Stream.eval(Task.now(2)) == Stream(2)`.

Streams have a small but powerful set of operations. The key operations are:

* `s ++ s2`: Concatenate the two streams.
* `s flatMap f`: Given `s: Stream[F,A]` and `f: A => Stream[F,B]`, returns a `Stream[F,B]`. This is the same concept as `flatMap` on `List`--it maps and then concatenates the inner streams.
* `s.onError(h)`: Given `s: Stream[F,A]` and `h: Throwable => Stream[F,A]`, this calls `h` with any exceptions if `s` terminates with an error. Thus `Stream.fail(err).onError(h) == h(err)`.
* `bracket(acquire)(use, release)`: Given `acquire: F[R]`, `use: R => Stream[F,W]`, and `release: R => F[Unit]`, produce a `Stream[F,W]` which evaluates the given `acquire` effect to produce a "resource", then guarantees that the `release` effect is evaluated when `use` terminates, whether with an error or with a result.

For the full set of operations primitive operations on `Stream`, see the [`Streams` trait](http://code.fs2.co/master/src/main/scala/fs2/Streams.scala), which the [`Stream` companion object](http://code.fs2.co/master/src/main/scala/fs2/Stream.scala) implements. There are only 11 primitive operations, and we've already seen most of them above! Note that for clarity, the primitives in `Streams` are defined in a `trait` as standalone functions, but for convenience these same functions are exposed with infix syntax on the `Stream` type. So `Stream.onError(s)(h)` may be invoked as `s.onError(h)`, and so on.

To interpret a `Stream[F,A]`, use `runFold`, `runLog`, or one of the other `run*` functions on `Stream`. These produce a `fs2.Free` which can then be interpreted to produce actual effects. Here's a complete example:

```scala
import fs2.Stream
// import fs2.Stream

import fs2.util.Task
// import fs2.util.Task

def run[A](s: Stream[Task,A]): Vector[A] =
  s.runLog.run.unsafeRun // explanation below
// run: [A](s: fs2.Stream[fs2.util.Task,A])Vector[A]

val s: Stream[Nothing,Int] = Stream((0 until 100): _*)
// s: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@31ae9d36

run(s) == Vector.range(0, 100)
// res0: Boolean = true
```

What's going on with all the `run` calls? The `s.runLog` produces a `fs2.Free[Task,Vector[A]]`. Calling `run` on that gives us a `Task[Vector[A]]`. And finally, calling `.unsafeRun` on that `Task` gives us our `Vector[A]`. Note that this last `.unsafeRun` is the _only_ portion of the code that actually _does_ anything. Streams (and `Free` and `Task`) are just different kinds of _descriptions_ of what is to be done. Nothing actually happens until we run this final description.

Note also that FS2 does not care what effect type you use for your streams. You may use the built-in [`Task` type](http://code.fs2.co/master/src/main/scala/fs2/Streams.scala) for effects or bring your own, just by implementing a few interfaces for your effect type. (`fs2.Catchable` and optionally `fs2.Async` if you wish to use various concurrent operations discussed later.)

### Performance

Regardless of how a `Stream` is built up, each operation takes constant time. So `p ++ p2` takes constant time, regardless of whether `p` is `Stream.emit(1)` or it's a huge stream with millions of elements and lots of embedded effects. Likewise with `p.onError(h)` and `p.flatMap(f)`. The runtime of these operations do not depend on the structure of `p`.

Achieving this trick is the job of the `runFold` function on `Stream`, which manipulates a _type-aligned sequence_ in an obvious stack-like fashion. But you don't need to worry about any of this to use the library. Focus on defining your streams in the most natural way and let FS2 do the heavy lifting!

## Transforming streams via the `Pull` and `Handle` types

We often wish to statefully transform one or more streams in some way, possibly evaluating effects as we do so. As a running example, consider taking just the first 5 elements of a `s: Stream[Task,Int]`. To produce a `Stream[Task,Int]` which takes just the first 5 elements of `s`, we need to repeadedly await (or pull) values from `s`, keeping track of the number of values seen so far and stopping as soon as we hit 5 elements. In more complex scenarios, we may want to evaluate additional effects as we pull from one or more streams.

Regardless of how complex the job, the `fs2.Pull` and `fs2.Stream.Handle` types can usually express it. `Handle[F,I]` represents a 'currently open' `Stream[F,I]`. We obtain one using `Stream.open`, or the method on `Stream`, `s.open`, which returns the `Handle` inside an effect type called `Pull`:

```Scala
// in fs2.Stream object
def open[F[_],I](s: Stream[F,I]): Pull[F,Nothing,Handle[F,I]]
```

The `trait Pull[+F[_],+W,+R]` represents a program that may pull values from one or more `Handle` values, write _output_ of type `W`, and return a _result_ of type `R`. It forms a monad in `R` and comes equipped with lots of other useful operations. See the [`Pulls` trait](http://code.fs2.co/master/src/main/scala/fs2/Pulls.scala) for the full set of primitive operations on `Pull`.

Let's look at the core operation for implementing `take`. It's just a recursive function:

```scala
import fs2._
// import fs2._

import fs2.Pull
// import fs2.Pull

import fs2.Stream.Handle
// import fs2.Stream.Handle

import fs2.Step._ // provides '#:' constructor, also called Step
// import fs2.Step._

object Pull_ {

  def take[F[_],W](n: Int): Handle[F,W] => Pull[F,W,Handle[F,W]] =
    h => for {
      chunk #: h <- if (n <= 0) Pull.done else Pull.awaitLimit(n)(h)
      tl <- Pull.output(chunk) >> take(n - chunk.size)(h)
    } yield tl

}
// defined object Pull_
```

Let's break it down line by line:

```Scala
chunk #: h <- if (n <= 0) Pull.done else Pull.awaitLimit(n)(h)
```

There's a lot going on in this one line:

* If `n <= 0`, we're done, and stop pulling.
* Otherwise we have more values to `take`, so we `Pull.awaitLimit(n)(h)`, which returns a `Step[Chunk[A],Handle[F,I]]` (again, inside of the `Pull` effect).
* The `Pull.awaitLimit(n)(h)` reads from the handle but gives us a `Chunk[W]` with _no more than_ `n` elements.
  * What's a `Chunk`? The `fs2.Chunk` type is a very simple strict sequence type. It doesn't need to be fancy because the `Stream` type does most of the heavy lifting. Of course, you are free to convert from `Chunk` to some other sequence type using `Chunk.foldLeft/Right`, `.toList`, or `.toVector`. If you know you are working with some primitive type like `Double` or `Byte` in your stream, you can also pattern match on a `Chunk` to obtain a specialized, unboxed `Chunk` and work directly with that to avoid unnecessary boxing and unboxing.
  * We can also `h.await1` to read just a single element, `h.await` to read a single `Chunk` of however many are available, `Pull.awaitN(n)(h)` to obtain a `List[Chunk[A]]` totaling exactly `n` elements, and even `h.awaitAsync` and various other _asynchronous_ awaiting functions which we'll discuss in the next section.
* Using the pattern `chunk #: h` (defined in `fs2.Step`), we destructure this `Step` to its `chunk: Chunk[W]` and its `h: Handle[F,W]`. This shadows the outer `h`, which is fine here since it isn't relevant anymore. (Note: nothing stops us from keeping the old `h` around and awaiting from it again if we like, though this isn't usually what we want since it will repeat all the effects of that await.)

Moving on, the `Pull.output(chunk)` writes the chunk we just read to the _output_ of the `Pull`. This binds the `W` type in our `Pull[F,W,R]` we are constructing:

```Scala
// in fs2.Pull object
def output[W](c: Chunk[W]): Pull[Nothing,W,Unit]
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
import fs2.{Pull,Stream}
// import fs2.{Pull, Stream}

def take[F[_],W](n: Int)(s: Stream[F,W]): Stream[F,W] =
  s.open.flatMap { Pull.take(n) }.run
// take: [F[_], W](n: Int)(s: fs2.Stream[F,W])fs2.Stream[F,W]

  // s.open.flatMap(f).run is a common pattern -
  // can be written `s.pull(f)`
```

FS2 takes care to guarantee that any resources allocated by the `Pull` are released when the `run` completes. Note again that _nothing happens_ when we call `.run` on a `Pull`, it is merely establishing a scope in which all resource allocations are tracked so that they may be appropriately freed by the `Stream.runFold` interpreter. This interpreter guarantees _once and only once_ semantics for resource cleanup actions introduced by the `Stream.bracket` function.

### Concurrency

TODO

## Talking to the external world

TODO

### Appendix A1: Sane subtyping with better error messages

`Stream[F,W]` and `Pull[F,W,R]` are covariant in `F`, `W`, and `R`. This is important for usability and convenience, but covariance can often paper over what should really be type errors. For instance:

```scala
     | Stream.emit(1) ++ Stream.emit("hello")
<console>:26: error: Dubious upper bound Any inferred for Int; supply `RealSupertype.allow[Int,Any]` here explicitly if this is not due to a type error
       Stream.emit(1) ++ Stream.emit("hello")
                      ^
```

We are trying to append a `Stream[Nothing,Int]` and a `Stream[Nothing,String]`, which really ought be a type error, but with covariance, Scala will gleefully infer `Any` as their common upper bound. Yikes. Luckily, FS2 implements a trick to catch these situations:

``` Scala
scala> import fs2._
import fs2._

scala> Stream.emit(1) ++ Stream("hi")
<console>:14: error: Dubious upper bound Any inferred for Int; supply `RealSupertype.allow[Int,Any]` here explicitly if this is not due to a type error
              Stream.emit(1) ++ Stream("hi")
                             ^
```

Informative! If you really want a dubious supertype like `Any`, `AnyRef`, `AnyVal`, `Product`, or `Serializable` to be inferred, just follow the instructions in the error message to supply a `RealSupertype` instance explicitly.

```scala
scala> import fs2._
import fs2._

scala> import fs2.util._
import fs2.util._

scala> Stream.emit(1).++(Stream("hi"))(RealSupertype.allow[Int,Any], Sub1.sub1[Task])
res4: fs2.Stream[fs2.util.Task,Any] = fs2.Stream$$anon$1@7ffe8ae6
```

Ugly, as it should be.
