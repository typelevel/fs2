# The README example, step by step

This walks through the implementation of the example given in [the README](../README.md). This program opens a file, `fahrenheit.txt`, containing temperatures in degrees fahrenheit, one per line, and converts each temperature to celsius, incrementally writing to the file `celsius.txt`. Both files will be closed, regardless of whether any errors occur.

```tut:book
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.{io, text, Stream}
import java.nio.file.Paths
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Converter extends IOApp {
  private val blockingExecutionContext =
    Resource.make(IO(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())))(ec => IO(ec.shutdown()))

  val converter: Stream[IO, Unit] = Stream.resource(blockingExecutionContext).flatMap { blockingEC =>
    def fahrenheitToCelsius(f: Double): Double =
      (f - 32.0) * (5.0/9.0)

    io.file.readAll[IO](Paths.get("testdata/fahrenheit.txt"), blockingEC, 4096)
      .through(text.utf8Decode)
      .through(text.lines)
      .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
      .map(line => fahrenheitToCelsius(line.toDouble).toString)
      .intersperse("\n")
      .through(text.utf8Encode)
      .through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blockingEC))
  }
  
  def run(args: List[String]): IO[ExitCode] =
    converter.compile.drain.as(ExitCode.Success)
}
```

Let's dissect this line by line.

`Stream[IO, Byte]` is a stream of `Byte` values which may periodically evaluate an `cats.effect.IO` in order to produce additional values. `Stream` is the core data type of FS2. It is parameterized on a type constructor (here, `IO`) which defines what sort of external requests it can make, and an output type (here, `Byte`), which defines what type of values it _emits_.

Operations on `Stream` are defined for any choice of type constructor, not just `IO`.

`fs2.io` has a number of helper functions for constructing or working with streams that talk to the outside world. `readAll` creates a stream of bytes from a file name (specified via a `java.nio.file.Path`). It encapsulates the logic for opening and closing the file, so that users of this stream do not need to remember to close the file when they are done or in the event of exceptions during processing of the stream.

```tut:silent
import cats.effect.{ContextShift, IO}
import fs2.{io, text}
import java.nio.file.Paths
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

//note: this should be shut down when it's no longer necessary - normally that's at the end of your app.
//See the whole README example for proper resource management in terms of ExecutionContexts.
val blockingExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

def fahrenheitToCelsius(f: Double): Double =
  (f - 32.0) * (5.0/9.0)
```

```tut
import fs2.Stream

val src: Stream[IO, Byte] =
  io.file.readAll[IO](Paths.get("testdata/fahrenheit.txt"), blockingExecutionContext, 4096)
```

A stream can be attached to a pipe, allowing for stateful transformations of the input values. Here, we attach the source stream to the `text.utf8Decode` pipe, which converts the stream of bytes to a stream of strings. We then attach the result to the `text.lines` pipe, which buffers strings and emits full lines. Pipes are expressed using the type `Pipe[F,I,O]`, which describes a pipe that can accept input values of type `I` and can output values of type `O`, potentially evaluating an effect periodically.

```tut
val decoded: Stream[IO, String] = src.through(text.utf8Decode)
val lines: Stream[IO, String] = decoded.through(text.lines)
```

Many of the functions defined for `List` are defined for `Stream` as well, for instance `filter` and `map`. Note that no side effects occur when we call `filter` or `map`. `Stream` is a purely functional value which can _describe_ a streaming computation that interacts with the outside world. Nothing will occur until we interpret this description, and `Stream` values are thread-safe and can be shared freely.

```tut
val filtered: Stream[IO, String] =
  lines.filter(s => !s.trim.isEmpty && !s.startsWith("//"))

val mapped: Stream[IO, String] =
  filtered.map(line => fahrenheitToCelsius(line.toDouble).toString)
```

Adds a newline between emitted strings of `mapped`.

```tut
val withNewlines: Stream[IO, String] = mapped.intersperse("\n")
```

We use another pipe, `text.utf8Encode`, to convert the stream of strings back to a stream of bytes.

```tut
val encodedBytes: Stream[IO, Byte] = withNewlines.through(text.utf8Encode)
```

We then write the encoded bytes to a file. Note that nothing has happened at this point -- we are just constructing a description of a computation that, when interpreted, will incrementally consume the stream, sending converted values to the specified file.

```tut
val written: Stream[IO, Unit] = encodedBytes.through(io.file.writeAll(Paths.get("testdata/celsius.txt"), blockingExecutionContext))
```

There are a number of ways of interpreting the stream. In this case, we call `compile.drain`, which returns a val value of the effect type, `IO`. The output of the stream is ignored - we compile it solely for its effect.

```tut
val task: IO[Unit] = written.compile.drain
```

We still haven't *done* anything yet. Effects only occur when we run the resulting task. We can run a `IO` by calling `unsafeRunSync()` -- the name is telling us that calling it performs effects and hence, it is not referentially transparent. In this example, we extended `IOApp`, which lets us express our overall program as an `IO[ExitCase]`. The `IOApp` class handles running the task and hooking it up to the application entry point.

Let's shut down the ExecutionContext that we allocated earlier.

```tut
blockingExecutionContext.shutdown()
```
