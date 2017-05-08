package fs2
package benchmark

import QuickProfile._

object FastBranchVsOld extends App {

  val N = 10000
  // use random initial value to prevent loop getting optimized away
  def init = (math.random * 10).toInt

  println("--- summation --- ")
  suite(
    timeit("segment push (100)") {
      import fs2.fast._
      (0 until 100).foldRight(Segment.single(0))((i,acc) => acc.push(Chunk.singleton(i))).fold(0)(_ + _).run
    },
    timeit("segment push (200)") {
      import fs2.fast._
      (0 until 200).foldRight(Segment.single(0))((i,acc) => acc.push(Chunk.singleton(i))).fold(0)(_ + _).run
    },
    timeit("segment push (400)") {
      import fs2.fast._
      (0 until 400).foldRight(Segment.single(0))((i,acc) => acc.push(Chunk.singleton(i))).fold(0)(_ + _).run
    },
    timeit("segment push (800)") {
      import fs2.fast._
      (0 until 800).foldRight(Segment.single(0))((i,acc) => acc.push(Chunk.singleton(i))).fold(0)(_ + _).run
    },
    timeit("segment append (100)") {
      import fs2.fast._
      (0 until 100).foldLeft(Segment.single(0))((acc,i) => acc ++ Segment.single(i)).fold(0)(_ + _).run
    },
    timeit("segment append (200)") {
      import fs2.fast._
      (0 until 200).foldLeft(Segment.single(0))((acc,i) => acc ++ Segment.single(i)).fold(0)(_ + _).run
    },
    timeit("segment append (400)") {
      import fs2.fast._
      (0 until 400).foldLeft(Segment.single(0))((acc,i) => acc ++ Segment.single(i)).fold(0)(_ + _).run
    },
    timeit("segment append (800)") {
      import fs2.fast._
      (0 until 800).foldLeft(Segment.single(0))((acc,i) => acc ++ Segment.single(i)).fold(0)(_ + _).run
    },
    timeit("segment appendr (800)") {
      import fs2.fast._
      (0 until 800).foldRight(Segment.single(0))((i,acc) => Segment.single(i) ++ acc).fold(0)(_ + _).run
    }
    //timeit("segment new") {
    //  import fs2.fast._
    //  Segment.from(init).take(N.toLong).sum(0L).run
    //},
    //timeit("new fs2") {
    //  import fs2.fast._
    //  def sum[F[_]](acc: Int, s: Stream[F,Int]): Pull[F,Int,Unit] =
    //    s.unsegment flatMap {
    //      case None => Pull.output1(acc)
    //      case Some((hd,s)) => sum(hd.fold(acc)(_ + _).run, s)
    //    }
    //  sum(init, Stream.range(0, N)).close.toVector.head
    //},
    //timeit("old fs2") {
    //  Stream.range(0, N).fold(init)(_ + _).toList.head.toLong
    //},
    //{ val nums = List.range(0, N)
    //  timeit("boxed loop") { nums.foldLeft(init)(_ + _) }
    //},
    //timeit("while loop") {
    //  var sum = init
    //  var i = 0
    //  while (i < N) { sum += i; i += 1 }
    //  sum
    //}
  )
  println("---")
}
