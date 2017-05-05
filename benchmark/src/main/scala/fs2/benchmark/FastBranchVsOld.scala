package fs2
package benchmark

import QuickProfile._

object FastBranchVsOld extends App {

  val N = 10000
  // use random initial value to prevent loop getting optimized away
  def init = (math.random * 10).toInt

  println("--- summation --- ")
  suite(
    timeit("segment new") {
      import fs2.fast._
      Segment2.from(init).take(N.toLong).sum(0L).run
    },
    timeit("segment old") {
      import fs2.fast._
      Segment.unfold(init) { i =>
        if (i < N) Some((i, i + 1))
        else None
      }.foldLeft(0L)(_ + _)
    },
    timeit("new fs2") {
      import fs2.fast._
      def sum[F[_]](acc: Int, s: Stream[F,Int]): Pull[F,Int,Unit] =
        s.unsegment flatMap {
          case None => Pull.output1(acc)
          case Some((hd,s)) => sum(hd.foldLeft(acc)(_ + _), s)
        }
      sum(init, Stream.range(0, N)).close.toVector.head
    },
    timeit("old fs2") {
      Stream.range(0, N).fold(init)(_ + _).toList.head.toLong
    },
    { val nums = List.range(0, N)
      timeit("boxed loop") { nums.foldLeft(init)(_ + _) }
    },
    timeit("while loop") {
      var sum = init
      var i = 0
      while (i < N) { sum += i; i += 1 }
      sum
    }
  )
  println("---")
}
