package fs2
package benchmark

import fs2.util._

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}


@State(Scope.Thread)
class TaskBenchmark extends BenchmarkUtils {

  implicit val s: Strategy = scaledStrategy

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

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def sumCurrent(n: Int): Int = sum(0, n)

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def sumSingle(n: Int): Int = Task(sum(0, n)).unsafeRun

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def sumMulti(n: Int): Int = {
    (1 to cores).map(_ => Task.start(Task(sum(0, n))) ).foldLeft(Task.now(Task.now(0))) { (b, x) =>
      b.flatMap(y => x.map(_.flatMap(xx => y.map(xx + _))))
    }.flatMap(identity).unsafeRun
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def sumRace(n: Int): Int = {
    (1 to cores).foldLeft(Task(sum(0, n)))((b, a) => b.race(Task(sum(0, n))).map(_.merge)).unsafeRun
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def incMap(n: Int): Int = {
    (1 to n).foldLeft(Task.now(0))((t, _) => t.map(identity)).unsafeRun
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def incFlatmap(n: Int): Int = {
    (1 to n).foldLeft(Task.now(0))((t, _) => t.flatMap(Task.now)).unsafeRun
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600)
  @Benchmark
  def traverseSingle(n: Int): Vector[Int] = {
    Task.traverse(0 to n)(Task.now).unsafeRun
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600)
  @Benchmark
  def traverseParallel(n: Int): Vector[Int] = {
    Task.parallelTraverse(0 to n)(Task.now).unsafeRun
  }

}

