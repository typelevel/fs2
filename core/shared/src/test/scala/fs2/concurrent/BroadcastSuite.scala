/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package concurrent

import cats.effect.IO
import org.scalacheck.effect.PropF.forAllF

class BroadcastSuite extends Fs2Suite {
  test("all subscribers see all elements") {
    forAllF { (source: Stream[Pure, Int], concurrent0: Int) =>
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
