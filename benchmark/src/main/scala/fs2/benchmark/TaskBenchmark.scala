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
  def sumCurrent: Int = {
    sum(range.start, range.end)
  }
  

  @Benchmark
  def sumSingle: Int = {
   taskSum.unsafeRun
  }
  
  @Benchmark
  def sumMulti: Int = {
    (1 to cores).map(_ => Task.start(taskSum) ).foldLeft(Task.now(Task.now(0))) { (b, x) =>
      b.flatMap(y => x.map(_.flatMap(xx => y.map(xx + _))))
    }.flatMap(identity).unsafeRun
  }

  @Benchmark
  def sumRace: Int = {
    (1 to cores).foldLeft(taskSum)((b, a) => b.race(taskSum).map(_.merge)).unsafeRun
  }

  //remove construction noise
  def cfold[A](a: A, end: Int)(f: (A, Int) => A): A = {
    var i = 0
    var na = a
    while(i < end) {
      i += 1
      na = f(na, i)
    }
    na
  }

  @Benchmark
  def incMap: Int = {
    cfold(Task.now(0), 10000)((t, _) => t.map(identity)).unsafeRun
  }

  @Benchmark
  def incFlatmap: Int = {
    cfold(Task.now(0), 10000)((t, _) => t.flatMap(Task.now)).unsafeRun
  }

  @Benchmark
  def traverseSingle: Vector[Int] = {
    Task.traverse(0 to 10000)(Task.now).unsafeRun
  }

  @Benchmark
  def traverseParallel: Vector[Int] = {
    Task.parallelTraverse(0 to 10000)(Task.now).unsafeRun
  }

}

