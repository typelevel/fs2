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
s1: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@7773b1c7

scala> val s1a = Stream(1,2,3) // variadic
s1a: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@77b7d542

scala> val s1b = Stream.emits(List(1,2,3)) // accepts any Seq
s1b: fs2.Stream[Nothing,Int] = fs2.Stream$$anon$1@1b9895b9
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
