# Start Here

Let's start by looking at a reasonably complete example. This program opens a file, `fahrenheit.txt`, containing temperatures in degrees fahrenheit, one per line, and converts each temperature to celsius, incrementally writing to the file `celsius.txt`. Both files will be closed, regardless of whether any errors occur.

```scala
scala> import fs2._
import fs2._

scala> import fs2.util.Task
import fs2.util.Task

scala> import java.nio.file.Paths
import java.nio.file.Paths

scala> def fahrenheitToCelsius(f: Double): Double =
     |   (f - 32.0) * (5.0/9.0)
fahrenheitToCelsius: (f: Double)Double

scala> val converter: Task[Unit] =
     |   io.file.readAll[Task](Paths.get("testdata/fahrenheit.txt"), 4096).
     |     through(text.utf8Decode).
     |     through(text.lines).
     |     filter(s => !s.trim.isEmpty && !s.startsWith("//")).
     |     map(line => fahrenheitToCelsius(line.toDouble).toString).
     |     intersperse("\n").
     |     through(text.utf8Encode).
     |     through(io.file.writeAll(Paths.get("testdata/celsius.txt"))).
     |     run.run
