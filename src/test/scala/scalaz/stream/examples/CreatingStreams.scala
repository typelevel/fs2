package scalaz.stream

import scalaz.concurrent.Task
import scalaz._
import scalaz.\/._
import Process.{Process1, Sink}

import org.scalacheck._
import Prop._

object CreatingStreams extends Properties("creating-streams") {
  
  /*

  Streams, sources, sinks, and stream transducers are
  represented in scalaz-stream using a single type: `Process`.
  This file introduces the basic `Process` type and shows how
  to create streams backed by synchronous and asynchronous
  external tasks.

  The `trait Process[F[_],O]` represents a stream of `O` 
  values which can interleave external requests to evaluate
  expressions of the form `F[A]` for any `A`. Where `F` 
  forms a `Monad`, this can be thought of as an effectful
  source or stream of `A` values.
  
  Many of the functions of scalaz-stream use `scalaz.concurrent.Task`
  as the `F` type, which means that the `Process` may issue
  asynchronous requests. `Task` is used as a substitute for
  `IO` that allows for parallelism.
  
  */
  
  property("examples") = secure {

    /* 
    A stream which is everywhere and forever equal to `1`.
    We will sometimes call this a 'continuous' stream, since
    it always has a value and there will never be a noticeable
    delay in obtaining its next element. 
    */
    val ones: Process[Task,Int] = Process.constant(1)

    /* A stream which emits `0, 1, 2 ... 99`. */
    val zeroTo100: Process[Task,Int] = Process.range(0,100) 

    /*
    Using `Process.eval`, we can promote any `F[A]` to 
    a `Process[F,A]`. Here, we promote the synchronous task
    `Task.delay("hi")` to a stream which emits the value
    `"hi"` then halts. Note that no effects are _run_ when
    calling `eval`. `eval(t)` simply produces a `Process` 
    which, when run, will run `t` and emit its result.
    */
    val greeting: Process[Task,String] = Process.eval { Task.delay("hi") }

    /*
    This process will emit `"hi"` forever. The `repeat`
    combinator works for any `Process`, `p`. When `p`
    halts normally (without an error), it is resumed
    from its initial state.
    */
    val greetings: Process[Task,String] = greeting.repeat

    /* This emits `0, 1, 2 ... 99, 0, 1, 2 ... 99, 0, 1 ...` */
    val cycleZeroTo100: Process[Task,Int] = zeroTo100.repeat
    
    /*
    A `Process[Task,A]` may also be backed by asynchronous tasks
    When run, this `Task` will submit `41 + 1` to an `ExecutorService`
    for evaluation. The `ExecutorService` may be explicitly
    specified as an optional second argument to `Task.apply`.
    */
    val expensiveInt: Task[Int] = Task { 41 + 1 }
    val expensiveInts: Process[Task,Int] = Process.eval(expensiveInt).repeat
    
    /* 
    
    We can also produce a stream by wrapping some callback-based
    API. For instance, here's a hypothetical API that we'd rather
    not use directly:

    */

    def asyncReadInt(callback: Throwable \/ Int => Unit): Unit = {
      // imagine an asynchronous task which eventually produces an `Int`
      try { 
        Thread.sleep(50) 
        val result = (math.random * 100).toInt
        callback(right(result))
      } catch { case t: Throwable => callback(left(t)) }
    }

    /* We can wrap this in `Task`, which forms a `Monad` */
    val intTask: Task[Int] = Task.async(asyncReadInt)

    /*
    And we can now produce a stream backed by repeated 
    asynchronous invocations of `intTask`. We sometimes call
    this a discrete stream, since it is only defined at certain
    times, when the asynchronous task produces a result.
    */
    val asyncInts: Process[Task,Int] = Process.eval(intTask).repeat

    /* Equivalently, we can use `Process.repeatEval` */
    val asyncInts2: Process[Task,Int] = Process.repeatEval(intTask)
    
    /* 

    With any of these streams, we can apply transformations like
    `map`, `filter`, `flatMap`, and so on. See 
    `TransformingStreams.scala` for more examples of the various 
    functions for transforming streams.

    */

    /* Again, nothing actually happens until we run this `Process`. */
    val oddsGt10: Process[Task,String] =
      asyncInts.take(25)
               .filter(_ > 10)
               .filter(_ % 2 != 0)
               .map(_.toString)
    
    /* 
    `runLog` returns the sequence of values emitted by a source,
    as a `Task`. Nothing happens until we run this `Task`, and
    we can run this `Task` again to repeat the entire `Process`
    */
    val r: Task[collection.immutable.IndexedSeq[String]] = oddsGt10.runLog

    /*
    At the end of the universe, we can `run` the `Task`
    Since we've filtered to allow only values greater than 10, we
    check that all values emitted satisfy this predicate.
    */
    val ok1: Boolean = r.run.forall(i => i.toInt > 10)
    
    /*
    Recall we can use `run` if we want to evaluate a stream just
    for its effects. Generally, we'll use `run` rather than using
    `collect`. If we want to do something further with the values 
    emitted from a stream, we can just feed the stream to another
    transformation, sink, or channel. See `TransformingStreams.scala`
    for more examples of this. Here's `t1` adapted:
    */
    val ok2: Task[Boolean] = 
      oddsGt10.pipe(process1.forall(i => i.toInt > 10)) // transform the stream using `forall`
              .runLastOr(true) // obtain the last value emitted, or `true` if stream is empty

    ok1 && ok2.run 
  }
}
