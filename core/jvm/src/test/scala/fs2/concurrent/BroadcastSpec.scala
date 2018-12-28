package fs2.concurrent

import cats.effect.IO
import fs2._
import TestUtil._

class BroadcastSpec extends Fs2Spec {

  "Broadcast" - {

    "all subscribers see all elements" in {
      forAll { (source: PureStream[Int], concurrent: SmallPositive) =>
        val expect = source.get.compile.toVector.map(_.toString)

        def pipe(idx: Int): Pipe[IO, Int, (Int, String)] =
          _.map { i =>
            (idx, i.toString)
          }

        val result =
          source.get
            .broadcastThrough((0 until concurrent.get).map(idx => pipe(idx)): _*)
            .compile
            .toVector
            .map(_.groupBy(_._1).map { case (k, v) => (k, v.map(_._2).toVector) })
            .unsafeRunSync()

        if (expect.nonEmpty) {
          result.size shouldBe (concurrent.get)
          result.values.foreach { v =>
            v shouldBe expect
          }
        } else {
          result.values.size shouldBe 0
        }

      }
    }

  }

}
