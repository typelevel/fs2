# Contributing

If you want to contribute to the project, you can find some helpful information here.

## How can I help?

Thanks for taking interest in helping us develop FS2!

We welcome all kinds of contribution, including but not limited to:

- 📖 documentation improvements, explanatory images/diagrams, fixes in typos, useful links
- 🧹 refactorings of messy code, build structure, increasing test coverage or quality
- 🚀 new features and bugfixes (including [bug reports and feature requests][fs2-issues]).

Writing documentation is valuable for learning, so if you find some explanation insufficient, overly complicated or incorrect, it's a perfect opportunity to make a change to it!

If at any point you run into problems, you can always ask a question on [the fs2-dev Gitter channel][fs2-dev].

## How to submit a change

If you see something worth adding, make the relevant changes in a fork of the source code and [submit a pull request to the project](fs2-pulls). If you don't know what you could help with, take a look at [the issues marked as "help wanted"][low-hanging-fruit] or ask on [Gitter][fs2-dev].

We follow similar rules to [the cats-effect project's](https://github.com/typelevel/cats-effect#development).
Most importantly, any contributions are expected to be made in the form of [GitHub pull requests to the FS2 repository][fs2-pulls].
Usually it takes two approvals for a change to be merged. If it takes too long to get it approved, feel free to ask on [the Gitter channel][fs2-dev].

Remember to follow the [code of conduct][coc] in online and offline discourse.

## Building the project locally

### Prerequisites

You'll need JDK 11 and [sbt][sbt].

We use several sbt plugins to build and check the project, including [MiMa (Migration Manager)][mima], [scalafmt][scalafmt] and [sbt-microsites][sbt-microsites].

### Build process

To compile the code for the whole repository, you can start an interactive sbt shell:

```bash
$ sbt
[info] Loading global plugins from /Users/contributor/.sbt/1.0/plugins
[info] Loading settings for project fs2-build from plugins.sbt,metals.sbt ...
[info] Loading project definition from /Users/contributor/dev/fs2/project
[info] Loading settings for project root from version.sbt,build.sbt ...
[info] Set current project to root (in build file:/Users/contributor/dev/fs2/)
sbt:root>
```

Inside the shell, you can compile the sources for the currently selected Scala version using the `compile` command.
To compile the code for all Scala versions enabled in the build, use `+compile`. To include tests, `test:compile` or `+test:compile`, accordingly.


### Testing

To test the code, you can run the `testJVM` or `testJS` command, depending on the platform you want to run the tests on.

Before submitting a change for review, it's worth running some extra checks that will be triggered in Continuous Integration:

```sbt
sbt:root> fmtCheck; testJVM; testJS; doc; mimaReportBinaryIssues; docs/mdoc; microsite/makeMicrosite
```

That will check the formatting, run all tests on the JVM and JS platforms, report detected binary compatibility issues (as detected by [MiMa][mima]) and build the site.

If you run into any problems with tests, binary compatibility or other issues, feel free to ask questions on [the Gitter channel][fs2-dev].

### Website

To build the microsite, use the `microsite/makeMicrosite` task in sbt. Then, go to `site/target/site`, and run a HTTP server on the port of your choice:

```bash
sbt:root> microsite/makeMicrosite
info: Compiled in 24s (0 errors, 0 warnings)
[info] Configuration file: /Users/contributor/dev/fs2/site/target/scala-2.13/resource_managed/main/jekyll/_config.yml
[info]             Source: /Users/contributor/dev/fs2/site/target/scala-2.13/resource_managed/main/jekyll
[info]        Destination: /Users/contributor/dev/fs2/site/target/jekyll
[info]  Incremental build: disabled. Enable with --incremental
[info]       Generating...
[info]                     done in 0.592 seconds.
[info]  Auto-regeneration: disabled. Use --watch to enable.
[success] Total time: 29 s, completed Jan 24, 2020, 2:36:31 PM
sbt:root> exit
[info] shutting down sbt server

$ cd site/target/site
$ python -m SimpleHTTPServer 4000 . # Any other server would do
Serving HTTP on 0.0.0.0 port 8080 ...
```


[fs2-issues]: https://github.com/functional-streams-for-scala/fs2/issues
[fs2-pulls]: https://github.com/functional-streams-for-scala/fs2/pulls
[fs2-dev]: https://gitter.im/functional-streams-for-scala/fs2-dev
[coc]: https://github.com/functional-streams-for-scala/fs2/blob/master/CODE_OF_CONDUCT.md
[sbt]: https://www.scala-sbt.org
[mima]: https://github.com/lightbend/mima
[scalafmt]: https://scalameta.org/scalafmt
[sbt-microsites]: https://47deg.github.io/sbt-microsites
[low-hanging-fruit]: https://github.com/functional-streams-for-scala/fs2/issues?q=is%3Aissue+is%3Aopen+sort%3Aupdated-desc+label%3A%22help+wanted%22
