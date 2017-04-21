package fs2.fast
package core

import Stream.Stream
import fs2.internal.TwoWayLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object Testing extends App {
  import fs2.Task
  implicit val S = scala.concurrent.ExecutionContext.Implicits.global

  val N = 10000
  val s = (0 until N).map(Stream.emit[Task,Int](_)).foldLeft(Stream.empty[Task,Int])(Stream.append(_,_))
  val s2 = (0 until N).map(fs2.Stream.emit).foldLeft(fs2.Stream.empty: fs2.Stream[Task,Int])(_ ++ _)
  // def s3f = (0 until N).map(Segment.single).foldLeft(Segment.empty : Segment[Int])(_ ++ _)
  def s3f = Segment.from(0).take(N).map(_.toInt)

  def printSum(s: Stream[Task,Int]) = println {
    Stream.runFold(s, 0)(_ + _, new AtomicBoolean(false), TwoWayLatch(0), new ConcurrentHashMap).unsafeRun()
  }
  printSum(s)
  println(s3f.foldLeft(0)(_ + _))
  // println(Segment.from(5).take(3))

  //timeit("segment init") {
  //  s3f
  //  42
  //}
  val s3 = s3f
  timeit("segment") {
    s3.foldLeft(0)(_ + _)
  }
  //timeit("unboxed") {
  //  var i = 0
  //  var sum = 0
  //  while (i < N) { sum += i; i += 1 }
  //  sum
  //}

  //timeit("new") {
  //  Stream.runFold(s, 0)(_ + _, new AtomicBoolean(false), TwoWayLatch(0), new ConcurrentHashMap).unsafeRun()
  //}
  //timeit("old") {
  //  s2.runFold(0)(_ + _).unsafeRun()
  //}

  def timeit(label: String, threshold: Double = 0.95)(action: => Long): Long = {
    // todo - better statistics to determine when to stop, based on
    // assumption that distribution of runtimes approaches some fixed normal distribution
    // (as all methods relevant to overall performance get JIT'd)
    var N = 64
    var i = 0
    var startTime = System.nanoTime
    var stopTime = System.nanoTime
    var sample = 1L
    var previousSample = Long.MaxValue
    var K = 0L
    var ratio = sample.toDouble / previousSample.toDouble
    while (sample > previousSample || sample*N < 1e8 || ratio < threshold) {
      previousSample = sample
      N = (N.toDouble*1.2).toInt ; i = 0 ; startTime = System.nanoTime
      while (i < N) { K += action; i += 1 }
      stopTime = System.nanoTime
      sample = (stopTime - startTime) / N
      ratio = sample.toDouble / previousSample.toDouble
      println(s"iteration $label: " + formatNanos(sample) + " (average of " + N + " samples)")
      System.gc()
    }
    println(label + ": " + formatNanos(sample) + " (average of " + N + " samples)")
    println("total number of samples across all iterations: " + K)
    sample
  }

  def formatNanos(nanos: Long) = {
    if (nanos > 1e9) (nanos.toDouble/1e9).toString + " seconds"
    else if (nanos > 1e6) (nanos.toDouble/1e6).toString + " milliseconds"
    else if (nanos > 1e3) (nanos.toDouble/1e3).toString + " microseconds"
    else nanos.toString + " nanoseconds"
  }
}
