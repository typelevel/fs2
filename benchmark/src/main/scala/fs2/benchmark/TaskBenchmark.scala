package fs2
package benchmark

import fs2.util._

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class TaskBenchmark {

  val range = 1 to 10000

  def sum(start: Int, end: Int): Int = {
    //use a while loop to not introduce any
    var i = start
    var sum = 0

    while(i < end) {
      i += 1
      sum += i
    }
    sum
  }

  def taskSum(implicit S: Strategy): Task[Int] = Task(sum(range.start, range.end))

  //to compare with
  @Benchmark
  def sumCurrentThread: Int = {
    sum(range.start, range.end)
  }

  @Benchmark
  def sumSingleThread: Int = {
   implicit val S: Strategy = Strategy.fromFixedDaemonPool(1)
   taskSum.unsafeRun
  }

  @Benchmark
  def sumMultiThread: Int = {
    implicit val S: Strategy = Strategy.fromFixedDaemonPool(4)
    (for {
      t1 <- Task.start { taskSum }
      t2 <- Task.start{ taskSum }
      t3 <- Task.start { taskSum }
      t4 <- Task.start { taskSum }
      r1 <- t1
      r2 <- t2
      r3 <- t3
      r4 <- t4
    } yield r1 + r2 + r3 + r4 ).unsafeRun
  }


}

