---
layout: page
title:  "Documentation and References"
section: "documentation"
position: 4
---

#### Talks and Presentations

* [Declarative Control Flow with fs2 Stream](https://www.youtube.com/watch?v=YSN__0VEsaw), a talk by [Fabio Labella][1] at the [Typelevel Summit](https://typelevel.org/event/2018-03-summit-boston/), celebrated in Boston in March 2018. Slides available at the author's [Github](https://github.com/SystemFw/TL-Summit-Boston-2018).
* [Compose your program flow with Stream](https://www.youtube.com/watch?v=x3GLwl1FxcA), a talk by [Fabio Labella][1] at a Klarna tech talk, sometime before March 2018. 
* 2018-03-20: [Compose your program flow with Stream](https://www.youtube.com/watch?v=x3GLwl1FxcA) by [Fabio Labella][1]
* [FS2 Internals: Performance](https://www.youtube.com/watch?v=TXxzMF14pxU), a talk by [Michael Pilquist][2] at [Scale by the Bay](http://2017.scale.bythebay.io/), in November 2017. [Slides](https://speakerdeck.com/mpilquist/fs2-internals).
* [Compositional Streaming with FS2](https://www.youtube.com/watch?v=oFk8-a1FSP0), a talk by [Michael Pilquist][2] at [Scale by the Bay](http://scala.bythebay.io/), November 2016. [Slides](https://speakerdeck.com/mpilquist/compositional-streaming-with-fs2).


#### Tutorials

* [An introduction to FS2 Streams](https://www.youtube.com/watch?v=B1wb4fIdtn4&t=3094s), a REPL-demo tutorial given by [Michael Pilquist][2] for the [Scala Toronto](https://www.meetup.com/scalator/events/254059603/) Meetup group, on September 2018.
* Introduction to Functional Streams for Scala (FS2), a REPL-demo tutorial on FS2 (version 0.9), given by [Michael Pilquist][2] in May of 2016. The tutorial is split in three parts:
  * [Part 1](https://www.youtube.com/watch?v=cahvyadYfX8).
  * [Part 2: Chunks & Pipes](https://www.youtube.com/watch?v=HM0mOu5o2uA).
  * [Part 3: Concurrency](https://www.youtube.com/watch?v=8YxcB6PIUDg).


#### Blog Posts and Short Articles

* [Inference Driven Design](https://mpilquist.github.io/blog/2018/07/04/fs2/), by [Muchael Pilquist][2], describes some of the tradeoffs in designing the API and the code of FS2, used to work around some of the problems in the Scala compiler. 
* [Tips for working with FS2](https://underscore.io/blog/posts/2018/03/20/fs2.html), by [Pere Villega](https://github.com/pvillega),
* [A streaming library with a superpower: FS2 and functional programming](https://medium.freecodecamp.org/a-streaming-library-with-a-superpower-fs2-and-functional-programming-6f602079f70a). 


#### Related Academic Research

* [Stream Fusion, to Completeness](https://arxiv.org/abs/1612.06668), by Oleg Kyseliov et al. In _Principles of Programming Languages (POPL)_, January 2017. [DBLP](https://dblp.uni-trier.de/rec/bibtex/conf/popl/KiselyovBPS17).

* [Iteratees](okmij.org/ftp/Haskell/Iteratee/describe.pdf), by Oleg Kyseliov, in _International Symposium of Functional and Logic Programming (FLOPS)_. [DBLP](https://dblp.uni-trier.de/rec/bibtex/conf/flops/Kiselyov12). [Author's webpage](http://okmij.org/ftp/Haskell/Iteratee/index.html).


#### Related Scala Libraries

* FS2 was originally called [Scalaz-Stream](https://github.com/scalaz/scalaz-stream). 
* [Monix](https://monix.io/) defines a special type for lazy, pull-based streaming, called `monix.tail.Iterant`.
* [ZIO](https://scalaz.github.io/) provides a stream type ([source code](https://github.com/scalaz/scalaz-zio/blob/master/core/shared/src/main/scala/scalaz/zio/stream/Stream.scala).
* [The FP in Scala stream processing library](https://github.com/fpinscala/fpinscala/blob/master/answers/src/main/scala/fpinscala/streamingio/StreamingIO.scala) developed for the book [FP in Scala](https://www.manning.com/books/functional-programming-in-scala)
* There are various other iteratee-style libraries for doing compositional, streaming I/O in Scala, notably the [`scalaz/iteratee`](https://github.com/scalaz/scalaz/tree/scalaz-seven/iteratee) package and [iteratees in Play](https://www.playframework.com/documentation/2.0/Iteratees).
* [Akka-Streams](https://doc.akka.io/docs/akka/2.5/stream/index.html)


#### Related Haskell Libraries

Since Haskell is the purely functional lazily-evaluated programming language _par excellance_, it is no wonder that many of the ideas in FS2 were first tried and developed in there.

* [Machines](https://github.com/ekmett/machines/), a Haskell library by Ed Kmett, which spawned [`scala-machines`](https://github.com/runarorama/scala-machines)
* [Conduit](http://hackage.haskell.org/package/conduit)
* [Pipes](http://hackage.haskell.org/package/pipes)
* [Reflex](https://hackage.haskell.org/package/reflex), an FRP library in Haskell, by Ryan Trinkle
* [Streaming](http://hackage.haskell.org/package/streaming) is a recent addition to Haskell streaming libraries. It represents a Stream using a `FreeT` monad transformer.


[1]: https://github.com/SystemFw
[2]: https://github.com/mpilquist


### Older References ###

The Github page for [Additional resources](https://github.com/functional-streams-for-scala/fs2/wiki/Additional-Resources) lists some of the references above and several older ones, mostly from the `scalaz-stream` days. 


