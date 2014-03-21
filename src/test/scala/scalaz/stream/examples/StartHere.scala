package scalaz.stream

import org.scalacheck._
import Prop._
import scalaz.concurrent.Task
import scodec.bits.ByteVector

object StartHere extends Properties("examples.StartHere") {
  
  /*

  Let's start by looking at a reasonably complete example.
  This program opens a file `fahrenheit.txt`, containing
  temperatures in degrees fahrenheit, one per line, and 
  converts each temperature to celsius, incrementally
  writing to the file `celsius.txt`. Both files will be
  closed, regardless of whether any errors occur.

  */

  property("simple file I/O") = secure {

    val converter: Task[Unit] = 
      io.linesR("testdata/fahrenheit.txt")
        .filter(s => !s.trim.isEmpty && !s.startsWith("//"))
        .map(line => fahrenheitToCelsius(line.toDouble).toString)
        .intersperse("\n")
        .pipe(text.utf8Encode)
        .to(io.fileChunkW("testdata/celsius.txt"))
        .run

    converter.run
    true
  }

  /*

  Let's dissect this line by line - 

  */

  property("dissected simple file I/O") = secure {
    
    /* 

    `Process[Task, String]` is a stream of `String` values which may
    periodically evaluate a `scalaz.concurrent.Task` in order to
    produce additional values. `Process` is the core data type of 
    `scalaz.stream`. It is parameterized on a type constructor (here, `Task`)
    which defines what sort of external requests it can make, and
    an output type (here, `String`), which defines what type of 
    values it _emits_.

    Many operations on `Process` are defined for any choice of 
    type constructor, not just `Task`.

    `scalaz.stream.io` has a number of helper functions for 
    constructing or working with streams that talk to the outside world.
    `lineR` creates a stream of lines from a filename. It encapsulates
    the logic for opening and closing the file, so that users of this
    stream do not need to remember to close the file when the are done
    or in the event of exceptions during processing of the stream.

    */

    val src: Process[Task, String] =
      io.linesR("testdata/fahrenheit.txt") 

    /* 

    Many of the functions defined for `List` are defined for 
    `Process` as well, for instance `filter` and `map`. 
    Note that no side effects occur when we call `filter` or `map`. 
    `Process` is a purely functional value which can _describe_
    a streaming computation that interacts with the outside
    world. Nothing will occur until we interpret this description,
    and `Process` values are thread-safe and can be shared freely.

    */

    val filtered: Process[Task, String] = 
      src.filter(s => !s.trim.isEmpty && !s.startsWith("//"))

    val mapped: Process[Task, String] = 
      filtered.map(line => fahrenheitToCelsius(line.toDouble).toString)

    /* Adds a newline between emitted strings of `mapped` */
    val withNewlines: Process[Task, String] = 
      mapped.intersperse("\n")
    
    /*
    
    We can pipe a `Process`-based stream through a stream transducer,
    called a `Process1`. This allows for stateful transformations
    of the input stream, for instance, if we want to emit a running
    count of the values seen so far.

    */

    val encoder: Process1[String, ByteVector] = text.utf8Encode

    val chunks: Process[Task, ByteVector] =
      withNewlines.pipe(encoder)

    val chunks2: Process[Task, ByteVector] = // alternate 'spelling' of `pipe`
      withNewlines |> encoder
    
    /*
    
    We can also represent sinks and effectful channels with `Process`.
    A sink is represented as a source of effectful functions.

    `fileChunkW` will open the file and close it when it is finished
    receiving values, or in the event of an error.

    */

    val sink: Process[Task, ByteVector => Task[Unit]] =
      io.fileChunkW("testdata/celsius.txt")
    
    val sink2: Sink[Task, ByteVector] = sink // `Sink` is a type alias for the above

    /*
    
    The `to` function sends an effectful stream to some `Sink`. Again, 
    nothing happens when we call it, we are just constructing a 
    description of a computation that, when interpreted, will 
    incrementally consume the stream, sending values to the given 
    `Sink`.

    Likewise, calling `pipeline.run` just returns a pure `Task` value.
    Only when we finally call `task.run` will this have the side effect 
    of opening `fahrenheit.txt` and `celsius.txt`, incrementally 
    transforming lines from `fahrenheit.txt` and sending them
    to `celsius.txt`, and then closing both files. If we do `task.run`
    again, the entire computation will be repeated, from opening the 
    files to closing them.

    */
    
    val pipeline: Process[Task, Unit] = 
      chunks.to(sink)

    val task: Task[Unit] = 
      pipeline.run
    
    /* This is the only place we actually have a side effect */
    val result: Unit = task.run

    true
  }


  def fahrenheitToCelsius(f: Double): Double = 
    (f - 32.0) * (5.0/9.0) 
}
