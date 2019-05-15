package fs2
package concurrent

import cats.effect.IO
import org.scalactic.anyvals.PosInt

class BroadcastSpec extends Fs2Spec {

  "Broadcast" - {

    "all subscribers see all elements" in {
      forAll { (source: Stream[Pure, Int], concurrent0: PosInt) =>
        val concurrent = concurrent0 % 20
        val expect = source.compile.toVector.map(_.toString)

        def pipe(idx: Int): Pipe[IO, Int, (Int, String)] =
          _.map { i =>
            (idx, i.toString)
          }

        source
          .broadcastThrough((0 until concurrent).map(idx => pipe(idx)): _*)
          .compile
          .toVector
          .map(_.groupBy(_._1).map { case (k, v) => (k, v.map(_._2).toVector) })
          .asserting { result =>
            if (expect.nonEmpty) {
              result.size shouldBe (concurrent)
              all(result.values) shouldBe expect
            } else {
              result.values.size shouldBe 0
            }
          }
      }
    }
  }
}
