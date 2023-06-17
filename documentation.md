# Documentation

#### Talks and Presentations

* [A sky full of streams](https://www.youtube.com/watch?v=oluPEFlXumw), a talk by [Jakub Kozłowski][kubukoz] at [Scala World](https://scala.world), a conference in the Lake District in the UK, in September 2019. Slides are available [on Speakerdeck](https://speakerdeck.com/kubukoz/a-sky-full-of-streams).
* [FS2 - Crash course](https://www.youtube.com/watch?v=tbShO8eIXbI), a talk by [Łukasz Byczyński][lukasz-byczynski] from [Scalar](https://scalar-conf.com) in Warsaw, in April 2019. [Examples are available on the author's GitHub](https://github.com/lukaszbyczynski/scalar-fs2-cc).
* [Declarative Control Flow with fs2 Stream](https://www.youtube.com/watch?v=YSN__0VEsaw), a talk by [Fabio Labella][systemfw] at the [Typelevel Summit](https://typelevel.org/event/2018-03-summit-boston/), celebrated in Boston in March 2018. Slides available at the author's [Github](https://github.com/SystemFw/TL-Summit-Boston-2018).
* [Compose your program flow with Stream](https://www.youtube.com/watch?v=x3GLwl1FxcA), a talk by [Fabio Labella][systemfw] at a Klarna tech talk, sometime before March 2018.
* 2018-03-20: [Compose your program flow with Stream](https://www.youtube.com/watch?v=x3GLwl1FxcA) by [Fabio Labella][systemfw]
* [FS2 Internals: Performance](https://www.youtube.com/watch?v=TXxzMF14pxU), a talk by [Michael Pilquist][mpilquist] at [Scale by the Bay](http://2017.scale.bythebay.io/), in November 2017. [Slides](https://speakerdeck.com/mpilquist/fs2-internals).
* [Compositional Streaming with FS2](https://www.youtube.com/watch?v=oFk8-a1FSP0), a talk by [Michael Pilquist][mpilquist] at [Scale by the Bay](http://scala.bythebay.io/), November 2016. [Slides](https://speakerdeck.com/mpilquist/compositional-streaming-with-fs2).


#### Tutorials

* [Basic streams and combinators in fs2](https://www.youtube.com/watch?v=TmhIabcu6Fs), a tutorial by [Jakub Kozłowski][kubukoz] introducing basic Stream operations and constructors.
* [Sharding a stream of values using Fs2](https://www.youtube.com/watch?v=FWYXqYQWAc0), a tutorial by [Gabriel Volpe][gvolpe] showcasing a specific use-case of fs2 streams.
* [An introduction to FS2 Streams](https://www.youtube.com/watch?v=B1wb4fIdtn4), a REPL-demo tutorial given by [Michael Pilquist][mpilquist] for the [Scala Toronto](https://www.meetup.com/scalator/events/254059603/) Meetup group, on September 2018.
* Introduction to Functional Streams for Scala (FS2), a REPL-demo tutorial on FS2 (version 0.9), given by [Michael Pilquist][mpilquist] in May of 2016. The tutorial is split in three parts:
  * [Part 1](https://www.youtube.com/watch?v=cahvyadYfX8).
  * [Part 2: Chunks & Pipes](https://www.youtube.com/watch?v=HM0mOu5o2uA).
  * [Part 3: Concurrency](https://www.youtube.com/watch?v=8YxcB6PIUDg).


#### Blog Posts and Short Articles
* [Leverage FS2 API to write to a file](https://davidfrancoeur.com/post/file-writing-fs2/) by [David Francoeur][francoeurdavid] describes writing data to files using the FS2 API.
* [Build a Stream to fetch everything behind an offset paged API](https://davidfrancoeur.com/post/paged-api-fs2/) by [David Francoeur][francoeurdavid] describes how to gather all issues for a Gitlab project.
* [Scala FS2 — handle broken CSV lines](https://medium.com/se-notes-by-alexey-novakov/scala-fs2-handle-broken-csv-lines-8d8c4defcd88) by [Alexey Novakov][alexey_novakov] describes processing fragmented text data using FS2.
* [Writing a simple Telegram bot with tagless final, http4s and fs2](https://pavkin.ru/writing-a-simple-telegram-bot-with-tagless-final-http4s-and-fs2/) by [Vladimir Pavkin][vpavkin] describes a process of writing a purely functional client for the Telegram messenger.
* [Inference Driven Design](https://mpilquist.github.io/blog/2018/07/04/fs2/), by [Michael Pilquist][mpilquist], describes some of the tradeoffs in designing the API and the code of FS2, used to work around some of the problems in the Scala compiler.
* [Tips for working with FS2](https://underscore.io/blog/posts/2018/03/20/fs2.html), by [Pere Villega](https://github.com/pvillega),
* [A streaming library with a superpower: FS2 and functional programming](https://medium.freecodecamp.org/a-streaming-library-with-a-superpower-fs2-and-functional-programming-6f602079f70a).
* [No leftovers: Working with pulls in fs2](https://blog.kebab-ca.se/chapters/fs2/overview.html), by [Zainab Ali](https://github.com/zainab-ali).

#### Books

* [Practical FP in Scala: A hands-on approach](https://leanpub.com/pfp-scala) by [Gabriel Volpe][gvolpe] contains a section on effectful, concurrent streaming with FS2.

#### Related Academic Research

* [Stream Fusion, to Completeness](https://arxiv.org/abs/1612.06668), by Oleg Kyseliov et al. In _Principles of Programming Languages (POPL)_, January 2017. [DBLP](https://dblp.uni-trier.de/rec/bibtex/conf/popl/KiselyovBPS17).

* [Iteratees](http://okmij.org/ftp/Haskell/Iteratee/describe.pdf), by Oleg Kyseliov, in _International Symposium of Functional and Logic Programming (FLOPS)_. [DBLP](https://dblp.uni-trier.de/rec/bibtex/conf/flops/Kiselyov12). [Author's webpage](http://okmij.org/ftp/Haskell/Iteratee/).


#### Related Scala Libraries

* FS2 was originally called [Scalaz-Stream](https://github.com/scalaz/scalaz-stream).
* [Monix](https://monix.io/) defines a special type for lazy, pull-based streaming, called `monix.tail.Iterant`.
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


[systemfw]: https://github.com/SystemFw
[mpilquist]: https://github.com/mpilquist
[kubukoz]: https://github.com/kubukoz
[gvolpe]: https://github.com/gvolpe
[vpavkin]: https://github.com/vpavkin
[lukasz-byczynski]: https://github.com/lukaszbyczynski
[francoeurdavid]: https://twitter.com/francoeurdavid
[alexey_novakov]: https://twitter.com/alexey_novakov

### Older References ###

The Github page for [Additional resources](https://github.com/functional-streams-for-scala/fs2/wiki/Additional-Resources) lists some of the references above and several older ones, mostly from the `scalaz-stream` days.
