package fs2
package concurrent

import cats.effect.IO
import org.scalacheck.effect.PropF.forAllF

class BalanceSuite extends Fs2Suite {
  test("all elements are processed") {
    forAllF { (source: Stream[Pure, Int], concurrent0: Int, chunkSize0: Int) =>
      val concurrent = (concurrent0 % 20).abs + 1
      val chunkSize = (chunkSize0.abs % 20).abs + 1
      val expected = source.toVector.map(_.toLong).sorted
      source
        .covary[IO]
        .balanceThrough(chunkSize = chunkSize, maxConcurrent = concurrent)(_.map(_.toLong))
        .compile
        .toVector
        .map(it => assert(it.sorted == expected))
    }
  }
}
