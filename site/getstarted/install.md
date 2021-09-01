# Install

The latest version for Cats Effect 3 is `3.1.1`, which supports Cats Effect 3 and is cross built for Scala 2.12, 2.13, and 3.0.

The latest version for Cats Effect 2 is `2.5.9`, which supports Cats Effect 2 and is similarly cross built for various Scala versions.

### Dependencies <!-- {docsify-ignore} -->

```
// available for 2.12, 2.13, 3.0
libraryDependencies += "co.fs2" %% "fs2-core" % "<version>"

// optional I/O library
libraryDependencies += "co.fs2" %% "fs2-io" % "<version>"

// optional reactive streams interop
libraryDependencies += "co.fs2" %% "fs2-reactive-streams" % "<version>"
```

The fs2-core library is also supported on Scala.js:

```
libraryDependencies += "co.fs2" %%% "fs2-core" % "<version>"
```

Release notes for each release are available on [Github](https://github.com/typelevel/fs2/releases/).

If upgrading from the 2.x series, see the [release notes for 3.0.0](https://github.com/typelevel/fs2/releases/tag/v3.0.0) for help with upgrading.

If upgrading from the 1.x series, see the [release notes for 2.0.0](https://github.com/typelevel/fs2/releases/tag/v2.0.0) for help with upgrading.

There are [detailed migration guides](https://github.com/typelevel/fs2/blob/main/docs/) for migrating from older versions.
