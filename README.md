FS2: Functional Streams for Scala (previously 'Scalaz-Stream')
=============

[![Build Status](https://travis-ci.org/functional-streams-for-scala/fs2.svg?branch=topic/redesign)](http://travis-ci.org/functional-streams-for-scala/fs2)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/functional-streams-for-scala/fs2)

We are currently in the process of completing a major reworking of this library. The new version, likely 0.9, should hopefully see a release in the next few months, and along with this release we are renaming the project to _FS2: Functional Streams for Scala_, or just FS2 for short (official pronunciation 'FS two'). The name is being changed as the new version will not depend on [scalaz](https://github.com/scalaz/scalaz) and will have a core with _zero dependencies_. Versions prior to 0.9 are still called scalaz-stream.

The 0.9 release will include major new capabilities:

* A new `Pull` datatype for building arbitrary transformations of any number of streams.
* Much richer support for concurrency and parallelism. Operations that previously needed special library support can now be defined with regular 'userland' FS2 code, using the existing primitive operations.
* The ability to read whole chunks on each step when transforming streams. This enables much higher performance. Existing transforms like `take`, `takeWhile`, etc are all being rewritten to take full advantage of this capability.
* Pushback and peeking of streams, useful for streaming parsing tasks.
* A simpler core, consisting of 22 primitive operations, from which all functionality in the library is derived.

There's [an incomplete and slightly out of date guide for the new design](https://github.com/functional-streams-for-scala/fs2/blob/topic/redesign/docs/guide.md) if you'd like to get a feel for what the new version will be like.

The rest of these docs pertain to the 0.8 scalaz-stream release. If you'd like to follow FS2 development, see [the 0.9 milestone](https://github.com/functional-streams-for-scala/fs2/milestones/0.9.0).

### Where to get the latest stable version ###

The latest stable release is 0.8 ([source](https://github.com/functional-streams-for-scala/fs2/tree/release/0.8)). To get it, add the following to your SBT build:

```
// available for Scala 2.10.5, 2.11.7, 2.12.0-M1, 2.12.0-M2
libraryDependencies += "org.scalaz.stream" %% "scalaz-stream" % "0.8"
```

As of version 0.8, scalaz-stream is solely published against scalaz 7.1.x.  The most recent build for 7.0.x is scalaz-stream 0.7.3.

If you were using a previous version of scalaz-stream, you may have a `resolvers` entry for the Scalaz Bintray repository.  This is no longer required, as scalaz-stream is now published to Maven Central.  It won't hurt you though.

### About the library ###

`scalaz-stream` is a streaming I/O library. The design goals are compositionality, expressiveness, resource safety, and speed. The design is meant to supersede or replace older iteratee or iteratee-style libraries. Here's a simple example of its use:

``` scala
import scalaz.stream._
import scalaz.concurrent.Task

val converter: Task[Unit] =
  io.linesR("testdata/fahrenheit.txt")
    .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
    .map(line => fahrenheitToCelsius(line.toDouble).toString)
    .intersperse("\n")
    .pipe(text.utf8Encode)
    .to(io.fileChunkW("testdata/celsius.txt"))
    .run

// at the end of the universe...
val u: Unit = converter.run
```

This will construct a `Task`, `converter`, which reads lines incrementally from `testdata/fahrenheit.txt`, skipping blanklines and commented lines. It then parses temperatures in degrees fahrenheit, converts these to celsius, UTF-8 encodes the output and writes incrementally to `testdata/celsius.txt`, using constant memory. The input and output files will be closed in the event of normal termination or exceptions.

The library supports a number of other interesting use cases:

* _Zipping and merging of streams:_ A streaming computations may read from multiple sources in a streaming fashion, zipping or merging their elements using a arbitrary `Tee`. In general, clients have a great deal of flexibility in what sort of topologies they can define--source, sinks, and effectful channels are all first-class concepts in the library.
* _Dynamic resource allocation:_ A streaming computation may allocate resources dynamically (for instance, reading a list of files to process from a stream built off a network socket), and the library will ensure these resources get released in the event of normal termination or when errors occur.
* _Nondeterministic and concurrent processing:_ A computation may read from multiple input streams simultaneously, using whichever result comes back first, and a pipeline of transformation can allow for nondeterminism and queueing at each stage.
* _Streaming parsing (UPCOMING):_ A separate layer handles constructing streaming parsers, for instance, for streaming JSON, XML, or binary parsing. See [the roadmap](https://github.com/functional-streams-for-scala/fs2/wiki/Roadmap) for more information on this and other upcoming work.

### Documentation and getting help ###

There are examples (with commentary) in the test directory [`scalaz.stream.examples`](https://github.com/scalaz/scalaz-stream/tree/master/src/test/scala/scalaz/stream/examples). Also see [the wiki](https://github.com/functional-streams-for-scala/fs2/wiki) for more documentation. If you use `scalaz.stream`, you're strongly encouraged to submit additional examples and add to the wiki!

For questions about the library, use the [scalaz mailing list](https://groups.google.com/forum/#!forum/scalaz) or the [scalaz-stream tag on StackOverflow](http://stackoverflow.com/questions/tagged/scalaz-stream).

Blog posts and other external resources are listed on the [Additional Resources](https://github.com/functional-streams-for-scala/fs2/wiki/Additional-Resources) page.

### Projects using scalaz-stream ###

If you have a project you'd like to include in this list, send a message to the [scalaz mailing list](https://groups.google.com/forum/#!forum/scalaz) and we'll add a link to it here.

* [http4s](http://http4s.org/): Minimal, idiomatic Scala interface for HTTP services using scalaz-stream
* [scodec-stream](https://github.com/scodec/scodec-stream): A library for streaming binary decoding and encoding, built using scalaz-stream and [scodec](https://github.com/scodec/scodec)
* [streamz](https://github.com/krasserm/streamz): A library that allows a `Process` to consume from and produce to [Apache Camel](http://camel.apache.org/) endpoints, [Akka Persistence](http://doc.akka.io/docs/akka/2.3.5/scala/persistence.html) journals and snapshot stores and [Akka Stream](http://akka.io/docs/#akka-streams-and-http) flows (reactive streams) with full back-pressure support.

### Related projects ###

[Machines](https://github.com/ekmett/machines/) is a Haskell library with the same basic design as `scalaz-stream`, though some of the particulars differ. There is also [`scala-machines`](https://github.com/runarorama/scala-machines), which is an older, deprecated version of the basic design of `scalaz-stream`.

There are various other iteratee-style libraries for doing compositional, streaming I/O in Scala, notably the [`scalaz/iteratee`](https://github.com/scalaz/scalaz/tree/scalaz-seven/iteratee) package and [iteratees in Play](https://www.playframework.com/documentation/2.0/Iteratees).
