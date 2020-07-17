package fs2
package concurrent

import cats.effect.IO

class BroadcastSuite extends Fs2Suite {
  test("all subscribers see all elements") {
    forAllAsync { (source: Stream[Pure, Int], concurrent0: Int) =>
      val concurrent = (concurrent0 % 20).abs
      val expect = source.compile.toVector.map(_.toString)

      def pipe(idx: Int): Pipe[IO, Int, (Int, String)] =
        _.map(i => (idx, i.toString))

      source
        .broadcastThrough((0 until concurrent).map(idx => pipe(idx)): _*)
        .compile
        .toVector
        .map(_.groupBy(_._1).map { case (k, v) => (k, v.map(_._2).toVector) })
        .map { result =>
          if (expect.nonEmpty) {
            assert(result.size == concurrent)
            result.values.foreach(it => assert(it == expect))
          } else
            assert(result.values.isEmpty)
        }
    }
  }
}
