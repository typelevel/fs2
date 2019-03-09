package fs2.concurrent

import cats.effect.IO
import fs2._
import TestUtil._

class BalanceSpec extends Fs2Spec {

  "Balance" - {

    "all elements are processed" in {
      forAll { (source: PureStream[Int], concurrent: SmallPositive, chunkSize: SmallPositive) =>
        val expected = source.get.compile.toVector.map(_.toLong).sorted

        val result =
          source.get
            .covary[IO]
            .balanceThrough(chunkSize = chunkSize.get, maxConcurrent = concurrent.get)(
              _.map(_.toLong))
            .compile
            .toVector
            .unsafeRunSync()
            .sorted

        result shouldBe expected

      }
    }

  }

}
