package fs2
package benchmark

import fs2.util._

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}


@State(Scope.Thread)
class TaskBenchmark extends BenchmarkUtils {

  implicit val s: Strategy = scaledStrategy
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

  def taskSum: Task[Int] = Task(sum(range.start, range.end))

  //to compare with
  @Benchmark
  def sumCurrentThread: Int = {
    sum(range.start, range.end)
  }
  

  @Benchmark
  def sumSingleThread: Int = {
   taskSum.unsafeRun
  }
  
  @Benchmark
  def sumMultiThread: Int = {
    (1 to cores).map(_ => Task.start(taskSum) ).foldLeft(Task.now(Task.now(0))) { (b, x) =>
      b.flatMap(y => x.map(_.flatMap(xx => y.map(xx + _))))
    }.flatMap(identity).unsafeRun
  }
}

