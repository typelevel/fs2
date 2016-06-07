package fs2
package benchmark

import fs2.util._

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class TaskBenchmark {

  val range = 1 to 1000000

  def sum(start: Int, end: Int): Int = {
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
  
  val singleThreadedStrategy = Strategy.fromFixedDaemonPool(1)

  @Benchmark
  def sumSingleThread: Int = {
   implicit val S: Strategy = singleThreadedStrategy
   taskSum.unsafeRun
  }
  
  val multiThreadedStrategy = Strategy.fromFixedDaemonPool(4)
  
  @Benchmark
  def sumMultiThread: Int = {
	//This needs to be removed
    implicit val S: Strategy = multiThreadedStrategy
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

