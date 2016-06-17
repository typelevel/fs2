package fs2
package benchmark

import fs2.util._

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}


@State(Scope.Thread)
class TaskBenchmark extends BenchmarkUtils {

  implicit val s: Strategy = scaledStrategy
  val range = 1 to 1000000

  //don't really want to create any objects in our benchmark...
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

  val incRange = 1 to 10000

  //don't measure construction time
  val _incMap = incRange.foldLeft(Task.now(0))((t, _) => t.map(identity))

  @Benchmark
  def incMap: Int = {
    _incMap.unsafeRun
  }

  //don't measure construction time
  val _incFlatMap = incRange.foldLeft(Task.now(0))((t, _) => t.flatMap(Task.now))

  @Benchmark
  def incFlatmap: Int = {
    _incFlatMap.unsafeRun
  }

  val traverseRange = 1 to 1000

  @Benchmark
  def traverseSingle: Vector[Int] = {
    Task.traverse(traverseRange)(Task.now).unsafeRun
  }

  @Benchmark
  def traverseParallel: Vector[Int] = {
    Task.parallelTraverse(traverseRange)(Task.now).unsafeRun
  }

}

