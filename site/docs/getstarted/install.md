# Install

See the badge at the top of the README to find out the latest version.

If upgrading from the 1.0 series, see the [release notes for 2.0.0](https://github.com/functional-streams-for-scala/fs2/releases/tag/v2.0.0) for help with upgrading.

### Dependencies <!-- {docsify-ignore} -->

```
// available for 2.12, 2.13, 3.0
libraryDependencies += "co.fs2" %% "fs2-core" % "<version>" // For cats 2 and cats-effect 2

// optional I/O library
libraryDependencies += "co.fs2" %% "fs2-io" % "<version>"

// optional reactive streams interop
libraryDependencies += "co.fs2" %% "fs2-reactive-streams" % "<version>"

// optional experimental library
libraryDependencies += "co.fs2" %% "fs2-experimental" % "<version>"
```

The fs2-core library is also supported on Scala.js:

```
libraryDependencies += "co.fs2" %%% "fs2-core" % "<version>"
```

There are [detailed migration guides](https://github.com/functional-streams-for-scala/fs2/blob/main/docs/) for migrating from older versions.
