package scalaz.stream 

import scalaz.concurrent.Task
import scalaz._
import scalaz.\/._
import Process.{Process1, Sink}

import org.scalacheck._
import Prop._

object WritingAndLogging extends Properties("writing-and-logging") {

  /*
  
  A `Writer[F,W,A]` wraps a `Process[F, W \/ A]` with some 
  convenience functions for working with either the written
  values (the `W`) or the output values (the `A`).

  This is useful for logging or other situations where we
  want to emit some values 'on the side' while doing something
  else with the main output of a `Process`.

  See `JournaledStreams.scala` for how `Writer` can be used
  to create persistent, distributed, or resumable streams.

  */

  property("writer") = secure {
    val W = Writer
    import W._

    val buf = new collection.mutable.ArrayBuffer[String] 

    /* 
    A full example, which we'll break down line by line
    in a minute. For each number in 0 to 10, this writes
    messages to the mutable `buf`: 

      Got input: 1
      Got input: 2
      ...

    The integers are still available for further transforms.
    */
    val ex: Process[Task,Int] = 
      Process.range(0,10)
             .flatMap(i => W.tell("Got input: " + i) ++ W.emitO(i))
             .drainW(io.fillBuffer(buf))
    
    /* This will have the side effect of filling `buf`. */  
    ex.run.run

    /* Let's break this down. */

    /* The original input. */
    val step0: Process[Task,Int] = Process.range(0,10)

    /* 
    Log some output using `W.tell`, and echo the original 
    input with `W.emitO` (`O` for 'output'). 
    */
    val step1: Writer[Task,String,Int] = 
      step0.flatMap { i => W.tell("Got input: " + i) ++ W.emitO(i) }

    /*
    A `Sink` which as a side effects writes to a mutable
    `Buffer`. This is more useful for testing.
    */
    val snk: Sink[Task,String] = io.fillBuffer(buf)

    /* 
    Another `Sink` we could use for our `Writer`, if
    we want to log the writes to standard out, with
    a newline after each `String`.
    */
    val snk2: Sink[Task,String] = io.stdOutLines

    /*
    The `drainW` function observes the write values of
    a `Writer` using some `Sink`, and then discards the
    write side of the writer to get back an ordinary 
    `Process`.
    */
    val step2: Process[Task,Int] = 
      step1.drainW(snk)

    /* Make sure all values got written to the buffer. */
    buf.toList == List.range(0,10).map("Got input: " + _) 
  }
}
