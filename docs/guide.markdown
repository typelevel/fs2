

_Unless otherwise noted, the type `Stream` mentioned in this document refers to the type `fs2.Stream` and NOT `scala.collection.immutable.Stream`._

## Building streams

A `Stream[F[_],W]` (formerly `Process`) represents a discrete stream of `W` values which may request evaluation of `F` effects. We'll call `F` the _effect type_ and `W` the _output type_. Here are some example streams (these assume you've done `import fs2.Stream`):

* `Stream.emit(1)`: A `Stream[Nothing,Int]`. Its effect type is `Nothing` since it requests evaluation of no effects.
* `Stream.empty`: A `Stream[Nothing,Nothing]` which emits no values. Its output type is `Nothing` since it emits no elements.
* `Stream.fail(err: Throwable)`: A `Stream[Nothing,Nothing]` which emits no values and fails with the error `err`.
* `Stream("hi","there","bob")`. A `Stream[Nothing,String]` which emits three values and has no effects.
* `Stream.eval(t: Task[Int])`. A `Stream[Task,Int]` which requests evaluation of `t` then emits the result of this evaluation. So `Stream.eval(Task.now(2)) == Stream(2)`.

Streams have a small but powerful set of operations. The key operations are:

* `s ++ s2`: Concatenate the two streams. Takes O(1).
* `s flatMap f`: Given `s: Stream[F,A]` and `f: A => Stream[F,B]`, returns a `Stream[F,B]`. The same concept as `flatMap` on `List`--it maps and the concatenates.
* `s.onError(h)`: Given `s: Stream[F,A]` and `h: Throwable => Stream[F,A]`, this calls `h` with any exceptions if `s` terminates with an error. Thus `Stream.fail(err).onError(h) == h(err)`.
* `bracket(acquire)(use, release)`: Given `acquire: F[R]`, `use: R => Stream[F,W]`, and `release: R => F[Unit]`, produce a `Stream[F,W]` which evaluates the given `acquire` effect to produce a "resource", then guarantees that the `release` effect is evaluated when `use` terminates, whether with an error or with a result.

For the full set of operations primitive operations on `Stream`, see the [`Streams` trait](http://code.fs2.co/master/src/main/scala/fs2/Streams.scala), which the [`Stream` companion object](http://code.fs2.co/master/src/main/scala/fs2/Stream.scala) implements. There are only 11 primitive operations, and we've already seen most of them above! Note that for clarity, the primitives in `Streams` are defined in a `trait` as standalone functions, but for convenience these same functions are exposed with infix syntax on the `Stream` type. So `Stream.onError(s)(h)` may be invoked as `s.onError(h)`, and so on.

To eventually run a `Stream[F,A]`, use `runFold`, `runLog`, or one of the other `run*` functions on `Stream`. These produce a `fs2.Free` which can then be interpreted to produce actual effects. Here's a complete example:

```Scala
import fs2.{Stream, Task}

def run[A](s: Stream[Task,A]): Vector[A] =
  s.runLog.run.run // explanation below

val s: Stream[Nothing,Int] = Stream((0 until 100): _*)
run(s) == Vector.range(0, 100)
```

What's going on with all the `.run` calls? The `s.runLog` produces a `fs2.Free[Task,Vector[A]]`. Calling `run` on that gives us a `Task[Vector[A]]`. And finally, calling `.run` on that `Task` gives us our `Vector[A]`. Note that this the _only_ portion of the code that actually _does_ anything. Streams (and `Free` and `Task`) are just different kinds of _descriptions_ of what is to be done. Nothing actually happens until we run this final description.

Note also that FS2 does not care what effect type you use for your streams. You may use the built-in [`Task` type](http://code.fs2.co/master/src/main/scala/fs2/Streams.scala) for effects or bring your own. Just implement a few interfaces for your effect type. (`fs2.Catchable` and optionally `fs2.Async` if you wish to use various concurrent operations.)

### Performance

Regardless of how a `Stream` is built up, each operation takes constant time. So `p ++ p2` takes constant time, regardless of whether `p` is just `Stream.emit(1)` or it's a huge stream with millions of elements. Likewise with `p.onError(h)` and `p.flatMap(f)`. The runtime of these operations do not depend on the structure of `p`.

Achieving this trick is the job of the `runFold` function on `Stream`, which manipulates a _type-aligned sequence_ in an obvious stack-like fashion.

The first, `s`, really ought to be a type error.

## Transforming streams

TODO

## Talking to the external world

TODO

### Sane subtyping with better error messages

`Stream[F,W]` and `Pull[F,W,R]` are covariant in `F`, `W`, and `R`. This is important for usability and convenience, but covariance can lead to bad type errors, or in some cases, no type errors at all! For instance:

``` Scala
val s = Stream.emit(1) ++ Stream.emit("hello")
```

We are trying to append a `Stream[Nothing,Int]` and a `Stream[Nothing,String]`, which really should be a type error, but with covariance, Scala will gleefully infer `Any` as their common upper bound. FS2 implements a trick to catch these situations:

``` Scala
scala> import fs2._
import fs2._

scala> Stream.emit(1) ++ Stream("hi")
<console>:14: error: Dubious upper bound Any inferred for Int; supply `RealSupertype.allow[Int,Any]` here explicitly if this is not due to a type error
              Stream.emit(1) ++ Stream("hi")
                             ^
```

Informative! If you really want a dubious supertype like `Any`, `AnyRef`, `AnyVal`, `Product`, or `Serializable` to be inferred, just follow the instruction to supply a `RealSupertype` instance explicitly.

``` Scala
scala> Stream.emit(1).++(Stream("hi"))(RealSupertype.allow[Int,Any])
res1: fs2.Stream[Nothing,Any] = fs2.Stream$$anon$4@2b7fa2d4
```

Ugly, as it should be.
