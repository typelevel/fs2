FS2: Functional Streams for Scala
=============

[![Continuous Integration](https://github.com/functional-streams-for-scala/fs2/workflows/Continuous%20Integration/badge.svg)](https://github.com/functional-streams-for-scala/fs2/actions?query=workflow%3A%22Continuous+Integration%22)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/functional-streams-for-scala/fs2)
[![Latest version](https://img.shields.io/maven-central/v/co.fs2/fs2-core_2.12.svg)](https://index.scala-lang.org/functional-streams-for-scala/fs2/fs2-core)

### Overview

FS2 is a library for purely functional, effectful, and polymorphic stream processing library in the [Scala programming language](https://scala-lang.org).
Its design goals are compositionality, expressiveness, resource safety, and speed.
The name is a modified acronym for **F**unctional **S**treams for **Scala** (FSS, or FS2).

FS2 is available for Scala 2.12, Scala 2.13, and [Scala.js](http://www.scala-js.org/).
FS2 is built upon two major functional libraries for Scala, [Cats](https://typelevel.org/cats/), and [Cats-Effect](https://typelevel.org/cats-effect/).
Regardless of those dependencies, FS2 core types (streams and pulls) are polymorphic in the effect type (as long as it is compatible with `cats-effect` typeclasses),
and thus FS2 can be used with other effect libraries, such as [Monix](https://monix.io/).

Prior to the 0.9 release in 2016, FS2 was known as `scalaz-stream`, which was based on the [`scalaz`](https://github.com/scalaz/scalaz) library.

### Getting Started

Quick links:

* [Microsite][microsite]
* [About the library](#about)
* [How to get latest version](#getit)
* API docs: [fs2-core][core-api], [fs2-io][io-api], [fs2-reactive-streams][rx-api], [fs2-experimental][experimental-api]
* [Docs and getting help](#docs)

[microsite]: http://fs2.io/index.html
[core-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/2.2.1/fs2-core_2.12-2.2.1-javadoc.jar/!/fs2/index.html
[io-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-io_2.12/2.2.1/fs2-io_2.12-2.2.1-javadoc.jar/!/fs2/io/index.html
[rx-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-reactive-streams_2.12/2.2.1/fs2-reactive-streams_2.12-2.2.1-javadoc.jar/!/fs2/interop/reactivestreams/index.html
[experimental-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-experimental_2.12/2.2.1/fs2-experimental_2.12-2.2.1-javadoc.jar/!/fs2/experimental/index.html

### <a id="getit"></a> Where to get the latest version ###

The latest version is 2.4.x. See the badge at the top of the README for the exact version number.

If upgrading from the 1.0 series, see the [release notes for 2.0.0](https://github.com/functional-streams-for-scala/fs2/releases/tag/v2.0.0) for help with upgrading.


```
// available for 2.12, 2.13
libraryDependencies += "co.fs2" %% "fs2-core" % "2.4.0" // For cats 2 and cats-effect 2

// optional I/O library
libraryDependencies += "co.fs2" %% "fs2-io" % "2.4.0"

// optional reactive streams interop
libraryDependencies += "co.fs2" %% "fs2-reactive-streams" % "2.4.0"

// optional experimental library
libraryDependencies += "co.fs2" %% "fs2-experimental" % "2.4.0"
```

The fs2-core library is also supported on Scala.js:

```
libraryDependencies += "co.fs2" %%% "fs2-core" % "2.4.0"
```

There are [detailed migration guides](https://github.com/functional-streams-for-scala/fs2/blob/main/docs/) for migrating from older versions.


### <a id="about"></a>Example ###

FS2 is a streaming I/O library. The design goals are compositionality, expressiveness, resource safety, and speed. Here's a simple example of its use:

```scala
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import fs2.{io, text, Stream}
import java.nio.file.Paths

object Converter extends IOApp {

  val converter: Stream[IO, Unit] = Stream.resource(Blocker[IO]).flatMap { blocker =>
    def fahrenheitToCelsius(f: Double): Double =
      (f - 32.0) * (5.0/9.0)

    io.file.readAll[IO](Paths.get("testdata/fahrenheit.txt"), blocker, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blocker))
  }

  def run(args: List[String]): IO[ExitCode] =
    converter.compile.drain.as(ExitCode.Success)
}
```

This will construct a program that reads lines incrementally from `testdata/fahrenheit.txt`, skipping blank lines and commented lines. It then parses temperatures in degrees Fahrenheit, converts these to Celsius, UTF-8 encodes the output, and writes incrementally to `testdata/celsius.txt`, using constant memory. The input and output files will be closed upon normal termination or if exceptions occur.

Note that this example is specialised to `IO` for simplicity, but `Stream` is fully polymorphic in the effect type (the `F[_]` in `Stream[F, A]`), as long as `F[_]` is compatible with the `cats-effect` typeclasses.

The library supports a number of other interesting use cases:

* _Zipping and merging of streams:_ A streaming computation may read from multiple sources in a streaming fashion, zipping or merging their elements using an arbitrary function. In general, clients have a great deal of flexibility in what sort of topologies they can define, due to `Stream` being a first class entity with a very rich algebra of combinators.
* _Dynamic resource allocation:_ A streaming computation may allocate resources dynamically (for instance, reading a list of files to process from a stream built off a network socket), and the library will ensure these resources get released upon normal termination or if exceptions occur.
* _Nondeterministic and concurrent processing:_ A computation may read from multiple input streams simultaneously, using whichever result comes back first, and a pipeline of transformations can allow for nondeterminism and queueing at each stage. Due to several concurrency combinators and data structures, streams can be used as light-weight, declarative threads to build complex concurrent behaviour compositionally.

These features mean that FS2 goes beyond streaming I/O to offer a very general and declarative model for arbitrary control flow.

### <a id="docs"></a>Documentation and getting help ###

* There are Scaladoc API documentations for [the core library][core-api], which defines and implements the core types for streams and pulls, as well as the type aliases for pipes and sinks. [The `io` library][io-api] provides FS2 bindings for NIO-based file I/O and TCP/UDP networking.
* [The official guide](https://fs2.io/guide.html) is a good starting point for learning more about the library.
* The [documentation page](https://fs2.io/documentation.html) is intended to serve as a list of all references, including conference presentation recordings, academic papers, and blog posts, on the use and implementation of `fs2`.
* [The FAQ](https://fs2.io/faq.html) has frequently asked questions. Feel free to open issues or PRs with additions to the FAQ!
* Also feel free to come discuss and ask/answer questions in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and/or on StackOverflow using [the tag FS2](http://stackoverflow.com/tags/fs2). Gitter will generally get you a quicker answer.

### Projects using FS2 ###

You can find a list of libraries and integrations with data stores built on top of FS2 here: [https://fs2.io/ecosystem.html](https://fs2.io/ecosystem.html).

If you have a project you'd like to include in this list, either open a PR or let us know in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and we'll add a link to it.

### Adopters ###

Here's a (non-exhaustive) list of companies that use FS2 in production. Don't see yours? You can [add it in a PR!](https://github.com/functional-streams-for-scala/fs2/edit/main/README.md)

* [Banno](https://banno.com/)
* [Chatroulette](https://about.chatroulette.com/)
* [Comcast](https://www.xfinity.com/)
* [CompStak](https://www.compstak.com)
* [Disney Streaming](https://www.disneyplus.com)
* [Formedix](https://www.formedix.com/)
* [OVO Energy](https://www.ovoenergy.com)
* [Ocado Technology](https://www.ocadogroup.com/technology/technology-pioneers)
* [Teikametrics](https://www.teikametrics.com)
* [IntentHQ](https://www.intenthq.com)

### Acknowledgments ###

[![YourKit](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

Special thanks to [YourKit](https://www.yourkit.com/) for supporting this project's ongoing performance tuning efforts with licenses to their excellent product.

### Code of Conduct ###

See the [Code of Conduct](https://github.com/functional-streams-for-scala/fs2/blob/main/CODE_OF_CONDUCT.md).
