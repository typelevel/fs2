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
s1: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@14abd688

scala> val s1a = Stream(1,2,3) // variadic
s1a: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@61ebf662

scala> val s1b = Stream.emits(List(1,2,3)) // accepts any Seq
s1b: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@8af3cbf
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
s1c: fs2.Stream[Nothing,Double] = fs2.Stream$$anon$1@4e1fa07f

scala> s1c.mapChunks {
     |   case ds : Chunk.Doubles => /* do things unboxed */ ds
     |   case ds => ds.map(_ + 1)
     | }
res8: fs2.Stream[Nothing,Double] = fs2.Stream$$anon$1@45833603
```

_Note:_ The `mapChunks` function is another library primitive. It's used to implement `map` and `filter`.

Let's look at some other example streams:

```scala
scala> val s2 = Stream.empty
s2: fs2.Stream[Nothing,Nothing] = fs2.Stream$$anon$1@71dcec54

scala> s2.toList
res9: List[Nothing] = List()

scala> import fs2.util.Task
import fs2.util.Task

scala> val eff = Stream.eval(Task.delay { println("TASK BEING RUN!!"); 1 + 1 })
eff: fs2.Stream[fs2.util.Task,Int] = fs2.Stream$$anon$1@350b4164
```

`Task` is an effect type we'll see a lot in these examples. Creating a `Task` has no side effects; nothing is actually run at the time of creation. Notice the type of `eff` is now `Stream[Task,Int]`.

The `eval` function works for any effect type, not just `Task`:

```Scala
def eval[F[_],A](f: F[A]): Stream[F,A]
```

_Note_: FS2 does not care what effect type you use for your streams. You may use the included [`Task` type][Task] for effects or bring your own, just by implementing a few interfaces for your effect type. ([`Catchable`][Catchable] and optionally [`Async`][Async] if you wish to use various concurrent operations discussed later.)

[Task]: ../../core/src/main/scala/fs2/util/Task.scala
[Catchable]: ../../core/src/main/scala/fs2/util/Catchable.scala
[Async]: ../../core/src/main/scala/fs2/util/Async.scala

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
res11: fs2.util.Task[Vector[Int]] = fs2.util.Task@4622e97a

scala> rb.run
res12: fs2.util.Task[Unit] = fs2.util.Task@32f7304f

scala> rc.run
res13: fs2.util.Task[Int] = fs2.util.Task@546aad3d
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
appendEx1: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@50d959e8

scala> val appendEx2 = Stream(1,2,3) ++ Stream.eval(Task.now(4))
appendEx2: fs2.Stream[fs2.util.Task,Int] = fs2.Stream$$anon$1@4ba1a313

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
err: fs2.Stream[Nothing,Nothing] = fs2.Stream$$anon$1@30f6db8b

scala> val err2 = Stream(1,2,3) ++ (throw new Exception("!@#$"))
err2: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@384dd09d

scala> val err3 = Stream.eval(Task.delay(throw new Exception("error in effect!!!")))
err3: fs2.Stream[fs2.util.Task,Nothing] = fs2.Stream$$anon$1@74f27e79
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
