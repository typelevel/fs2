# Handling Files

The package `fs2.io.file` contains code which allows reading from, and writing to, local
files. This is implemented using calls to the `java.nio` package.

## Important imports

```tut:book
import fs2._
import fs2.util.Task
import java.nio.file._
```

And for asynchronous calls, we will need a `Strategy` (this provides details on
  where to run callbacks, so we don't over-use NIO threads):
```tut:book
implicit val strategy = Strategy.fromCachedDaemonPool("async-file-io")
```

# Reading complete Files

For the common task of reading files sequentially (from the beginning) at a particular path, FS2 provides two convenience methods, depending on your need for asynchronous operations.

## Synchronous file reading

```tut:book
io.file.readAll[Task](Paths.get("build.sbt"), 1024)
```

## Asynchronous file reading

```tut:book
io.file.readAllAsync[Task](Paths.get("build.sbt"), 1024)
```

These close the file after it is no longer needed.

# Writing files sequentially

Similarly, two methods are provided which offer an easy way to write to
files sequentially from the beginning.

## Synchronous file writing

```tut:book
io.file.writeAll[Task](Files.createTempFile("xxx", "yyy"))
```

## Asynchronous file writing

```tut:book
io.file.writeAllAsync[Task](Files.createTempFile("xxx", "yyy"))
```

Again, these close the file when it is no longer needed.

# Example 1: Reading a UTF-8 file into a stream of `String`s

```tut:book
val source = io.file.readAll[Task](Paths.get("build.sbt"), 1024)

source.through(text.utf8Decode)
```

# Example 2: Copying a file

```tut:book
val destination = io.file.writeAll[Task](Paths.get("build.sbt.backup"))

source.to(destination).run.run
```

# Example 3: Copying a file, converting to lower-case

```tut:book
source.
  through(text.utf8Decode).
  map(_.toLowerCase).
  through(text.utf8Encode).
  to(destination).
  run.run
```

# The `FileHandle` trait

The `FileHandle` trait provides access to read or modify an "open" file.
As it is based on the features from Java NIO, it allows random access
to read and/or write, and also allows locking of all, or parts of, the file.
It can also be used to force synchronization of a file to disk, and request
information on the file's size.

## Obtaining a FileHandle

A `FileHandle` can be obtained using a `java.nio.file.Path`, a `java.nio.channel.FileChannel`,
or a `java.nio.channel.AsynchronousFileChannel` object. This `FileHandle` is wrapped in a `Pull`,
and the appropriate resources will be closed when the `Pull` is complete.

### Code examples

```tut:book
io.file.pulls.fromPath[Task](Paths.get("build.sbt"), List(StandardOpenOption.READ))
io.file.pulls.fromPathAsync[Task](Paths.get("build.sbt"), List(StandardOpenOption.WRITE))
```

We can create a channel from a `FileInputStream` or `FileOutputStream`:

```tut:book
io.file.pulls.fromFileChannel[Task](
  Task.delay(
    new java.io.FileInputStream(new java.io.File("build.sbt")).getChannel
  )
)
```
We can also open files asynchronously with the `io.file.pulls.fromAsynchronousFileChannel` call.

## The `Pull` monad and `FileHandle` operations

Giving the ability to manipulate files within the `Pull` monad provides
a lot of power. It is instructive to look at one of the simpler implementations, [pulls.readAllFromFileHandle](https://github.com/functional-streams-for-scala/fs2/tree/topic/redesign/io/src/main/scala/fs2/io/file/pulls.scala).

As Java NIO doesn't include the concept of a current location within a file, the `Pull` has to keep the state of how far has been read, and read the next chunk of data starting
at that offset.

The implementation makes use of a few core ideas:

 - `Pull.eval(fileHandle.op(...))`

    Use `Pull.eval` to make use of a `FileHandle` feature
 - `Pull.output`

    Use `Pull.output` to emit values visible in the stream
 - `Pull.done`

    Use to indicate that processing is finished (file is completely read).
 - We use `Pull.flatMap` and a recursive call, to continue the "program"
   inside the `Pull` until `Pull.done` is reached.
