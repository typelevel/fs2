package fs2
package benchmark

import scala.concurrent.ExecutionContext.Implicits.global
import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

import fs2.util.Concurrent

@State(Scope.Thread)
class TaskBenchmark {

  val cores: Int = Runtime.getRuntime.availableProcessors

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
  def current(N: Int): Int = sum(0, N)

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def apply(N: Int): Int = Task(sum(0, N)).unsafeRun()

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def start(N: Int): Int = {
    (1 to cores).map(_ => Task.start(Task(sum(0, N))) ).foldLeft(Task.now(Task.now(0))) { (b, x) =>
      b.flatMap(y => x.map(_.flatMap(xx => y.map(xx + _))))
    }.flatMap(identity).unsafeRun()
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def race(N: Int): Int = {
    (1 to cores).foldLeft(Task(sum(0, N)))((b, a) => Concurrent[Task].race(b, Task(sum(0, N))).map(_.merge)).unsafeRun()
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def map(N: Int): Int = {
    (1 to N).foldLeft(Task.now(0))((t, _) => t.map(identity)).unsafeRun()
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
  @Benchmark
  def flatMap(N: Int): Int = {
    (1 to N).foldLeft(Task.now(0))((t, _) => t.flatMap(Task.now)).unsafeRun()
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600)
  @Benchmark
  def traverse(N: Int): Vector[Int] = {
    Task.traverse(0 to N)(Task.now).unsafeRun()
  }

  @GenerateN(1, 4, 100, 200, 400, 800, 1600)
  @Benchmark
  def parallelTraverse(N: Int): Vector[Int] = {
    Task.parallelTraverse(0 to N)(Task.now).unsafeRun()
  }

}
