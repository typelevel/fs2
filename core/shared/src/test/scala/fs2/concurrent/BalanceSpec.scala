package fs2
package concurrent

import cats.effect.IO
import org.scalactic.anyvals.PosInt

class BalanceSpec extends Fs2Spec {

  "Balance" - {

    "all elements are processed" in {
      forAll { (source: Stream[Pure, Int], concurrent0: PosInt, chunkSize0: PosInt) =>
        val concurrent = concurrent0 % 20 + 1
        val chunkSize = chunkSize0 % 20 + 1
        val expected = source.toVector.map(_.toLong).sorted
        source
          .covary[IO]
          .balanceThrough(chunkSize = chunkSize, maxConcurrent = concurrent)(_.map(_.toLong))
          .compile
          .toVector
          .asserting(_.sorted shouldBe expected)
      }
    }
  }
}
