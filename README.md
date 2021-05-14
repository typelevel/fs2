FS2: Functional Streams for Scala
=============

[![Continuous Integration](https://github.com/functional-streams-for-scala/fs2/workflows/Continuous%20Integration/badge.svg)](https://github.com/functional-streams-for-scala/fs2/actions?query=workflow%3A%22Continuous+Integration%22)
[![Discord](https://img.shields.io/discord/632277896739946517.svg?label=&logo=discord&logoColor=ffffff&color=404244&labelColor=6A7EC2)](https://discord.gg/9V8FZTVZ9R)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/functional-streams-for-scala/fs2)
[![Maven Central](https://img.shields.io/maven-central/v/co.fs2/fs2-core_2.12)](https://maven-badges.herokuapp.com/maven-central/co.fs2/fs2-core_2.12)

### Overview

FS2 is a library for purely functional, effectful, and polymorphic stream processing library in the [Scala programming language](https://scala-lang.org).
Its design goals are compositionality, expressiveness, resource safety, and speed.
The name is a modified acronym for **F**unctional **S**treams for **Scala** (FSS, or FS2).

FS2 is available for Scala 2.12, Scala 2.13, Scala 3, and [Scala.js](http://www.scala-js.org/).
FS2 is built upon two major functional libraries for Scala, [Cats](https://typelevel.org/cats/), and [Cats-Effect](https://typelevel.org/cats-effect/).
Regardless of those dependencies, FS2 core types (streams and pulls) are polymorphic in the effect type (as long as it is compatible with `cats-effect` typeclasses),
and thus FS2 can be used with other effect libraries, such as [Monix](https://monix.io/).

Prior to the 0.9 release in 2016, FS2 was known as `scalaz-stream`, which was based on the [`scalaz`](https://github.com/scalaz/scalaz) library.

### Getting Started

Quick links:

* [Microsite][microsite]
* [About the library](#about)
* [How to get latest version](#getit)
* API docs: [fs2-core][core-api], [fs2-io][io-api], [fs2-reactive-streams][rx-api]
* [Docs and getting help](#docs)

[microsite]: http://fs2.io
[core-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12/3.0.0/fs2-core_2.12-3.0.0-javadoc.jar/!/fs2/index.html
[io-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-io_2.12/3.0.0/fs2-io_2.12-3.0.0-javadoc.jar/!/fs2/io/index.html
[rx-api]: https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-reactive-streams_2.12/3.0.0/fs2-reactive-streams_2.12-3.0.0-javadoc.jar/!/fs2/interop/reactivestreams/index.html

### <a id="docs"></a>Documentation and getting help ###

* There are Scaladoc API documentations for [the core library][core-api], which defines and implements the core types for streams and pulls, as well as the type aliases for pipes and sinks. [The `io` library][io-api] provides FS2 bindings for NIO-based file I/O and TCP/UDP networking.
* [The official guide](https://fs2.io/#/guide) is a good starting point for learning more about the library.
* The [documentation page](https://fs2.io/#/documentation) is intended to serve as a list of all references, including conference presentation recordings, academic papers, and blog posts, on the use and implementation of `fs2`.
* [The FAQ](https://fs2.io/#/faq) has frequently asked questions. Feel free to open issues or PRs with additions to the FAQ!
* Also feel free to come discuss and ask/answer questions in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and/or on StackOverflow using [the tag FS2](http://stackoverflow.com/tags/fs2). Gitter will generally get you a quicker answer.

### Projects using FS2 ###

You can find a list of libraries and integrations with data stores built on top of FS2 here: [https://fs2.io/#/ecosystem](https://fs2.io/#/ecosystem).

If you have a project you'd like to include in this list, either open a PR or let us know in [the gitter channel](https://gitter.im/functional-streams-for-scala/fs2) and we'll add a link to it.

### Acknowledgments ###

[![YourKit](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

Special thanks to [YourKit](https://www.yourkit.com/) for supporting this project's ongoing performance tuning efforts with licenses to their excellent product.

### Code of Conduct ###

See the [Code of Conduct](https://github.com/functional-streams-for-scala/fs2/blob/main/CODE_OF_CONDUCT.md).
