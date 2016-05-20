# The README example, step by step

This walks through the implementation of the example given in [the README](../README.md). This program opens a file, `fahrenheit.txt`, containing temperatures in degrees fahrenheit, one per line, and converts each temperature to celsius, incrementally writing to the file `celsius.txt`. Both files will be closed, regardless of whether any errors occur.

```scala
object Converter {
  import fs2.{io, text}
  import fs2.util.Task
  import java.nio.file.Paths

  def fahrenheitToCelsius(f: Double): Double =
    (f - 32.0) * (5.0/9.0)

  val converter: Task[Unit] =
    io.file.readAll[Task](Paths.get("testdata/fahrenheit.txt"), 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt")))
      .run.run
}
// defined object Converter

Converter.converter.unsafeRun
```

Let's dissect this line by line.

`Stream[Task, Byte]` is a stream of `Byte` values which may periodically evaluate an `fs2.util.Task` in order to produce additional values. `Stream` is the core data type of FS2. It is parameterized on a type constructor (here, `Task`) which defines what sort of external requests it can make, and an output type (here, `Byte`), which defines what type of values it _emits_.

Operations on `Stream` are defined for any choice of type constructor, not just `Task`.

`fs2.io` has a number of helper functions for constructing or working with streams that talk to the outside world. `readAll` creates a stream of bytes from a file name (specified via a `java.nio.file.Path`). It encapsulates the logic for opening and closing the file, so that users of this stream do not need to remember to close the file when they are done or in the event of exceptions during processing of the stream.

```scala
import fs2.{io, text}
import fs2.util.Task
import java.nio.file.Paths
import Converter._
```

```scala
scala> import fs2.Stream
import fs2.Stream

scala> val src: Stream[Task, Byte] =
     |   io.file.readAll[Task](Paths.get("testdata/fahrenheit.txt"), 4096)
src: fs2.Stream[fs2.util.Task,Byte] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)
```

A stream can be attached to a pipe, allowing for stateful transformations of the input values. Here, we attach the source stream to the `text.utf8Decode` pipe, which converts the stream of bytes to a stream of strings. We then attach the result to the `text.lines` pipe, which buffers strings and emits full lines. Pipes are expressed using the type `Pipe[F,I,O]`, which describes a pipe that can accept input values of type `I` and can output values of type `O`, potentially evaluating an effect periodically.

```scala
scala> val decoded: Stream[Task, String] = src.through(text.utf8Decode)
decoded: fs2.Stream[fs2.util.Task,String] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)

scala> val lines: Stream[Task, String] = decoded.through(text.lines)
lines: fs2.Stream[fs2.util.Task,String] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)
```

Many of the functions defined for `List` are defined for `Stream` as well, for instance `filter` and `map`. Note that no side effects occur when we call `filter` or `map`. `Stream` is a purely functional value which can _describe_ a streaming computation that interacts with the outside world. Nothing will occur until we interpret this description, and `Stream` values are thread-safe and can be shared freely.

```scala
scala> val filtered: Stream[Task, String] =
     |   lines.filter(s => !s.trim.isEmpty && !s.startsWith("//"))
filtered: fs2.Stream[fs2.util.Task,String] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)

scala> val mapped: Stream[Task, String] =
     |   filtered.map(line => fahrenheitToCelsius(line.toDouble).toString)
mapped: fs2.Stream[fs2.util.Task,String] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>).mapChunks(<function1>)
```

Adds a newline between emitted strings of `mapped`.

```scala
scala> val withNewlines: Stream[Task, String] = mapped.intersperse("\n")
withNewlines: fs2.Stream[fs2.util.Task,String] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)
```

We use another pipe, `text.utf8Encode`, to convert the stream of strings back to a stream of bytes.

```scala
scala> val encodedBytes: Stream[Task, Byte] = withNewlines.through(text.utf8Encode)
encodedBytes: fs2.Stream[fs2.util.Task,Byte] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>).flatMap(<function1>)
```

We then write the encoded bytes to a file. Note that nothing has happened at this point -- we are just constructing a description of a computation that, when interpreted, will incrementally consume the stream, sending converted values to the specified file.

```scala
scala> val written: Stream[Task, Unit] = encodedBytes.through(io.file.writeAll(Paths.get("testdata/celsius.txt")))
written: fs2.Stream[fs2.util.Task,Unit] = evalScope(Scope(Bind(Eval(Snapshot),<function1>))).flatMap(<function1>)
```

There are a number of ways of interpreting the stream. In this case, we call `run`, which returns a description of the program in the `Free` monad, where the output of the stream is ignored - we run it solely for its effect. That description can then interpreted in to a value of the effect type.

```scala
scala> val freeInterpretation: fs2.util.Free[Task, Unit] = written.run
freeInterpretation: fs2.util.Free[fs2.util.Task,Unit] = Bind(Bind(Pure(()),<function1>),<function1>)

scala> val task: Task[Unit] = freeInterpretation.run
task: fs2.util.Task[Unit] = Task
```

We still haven't *done* anything yet. Effects only occur when we run the resulting task. We can run a `Task` by calling `unsafeRun` -- the name is telling us that calling it performs effects and hence, it is not referentially transparent.

```scala
scala> val result: Unit = task.unsafeRun
result: Unit = ()
```
