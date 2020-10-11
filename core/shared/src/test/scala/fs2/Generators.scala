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

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary

trait Generators extends ChunkGenerators {

  implicit def pureStreamGenerator[A: Arbitrary]: Arbitrary[Stream[Pure, A]] =
    Arbitrary {
      val genA = arbitrary[A]
      Gen.frequency(
        1 -> Gen.const(Stream.empty),
        5 -> smallLists(genA).map(as => Stream.emits(as)),
        5 -> smallLists(genA).map(as => Stream.emits(as).unchunk),
        5 -> smallLists(smallLists(genA))
          .map(_.foldLeft(Stream.empty.covaryOutput[A])((acc, as) => acc ++ Stream.emits(as))),
        5 -> smallLists(smallLists(genA))
          .map(_.foldRight(Stream.empty.covaryOutput[A])((as, acc) => Stream.emits(as) ++ acc))
      )
    }

  implicit def effectfulStreamGenerator[F[_], O](implicit
      arbO: Arbitrary[O],
      arbFo: Arbitrary[F[O]],
      arbFu: Arbitrary[F[Unit]]
  ): Arbitrary[Stream[F, O]] =
    Arbitrary(
      Gen.frequency(
        10 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10)),
        10 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10).unchunk),
        5 -> arbitrary[F[O]].map(fo => Stream.eval(fo)),
        1 -> (for {
          acquire <- arbitrary[F[O]]
          release <- arbitrary[F[Unit]]
          use <- effectfulStreamGenerator[F, O].arbitrary
        } yield Stream.bracket(acquire)(_ => release).flatMap(_ => use))
      )
    )

  implicit def pullGenerator[F[_], O, R](implicit
      arbR: Arbitrary[R],
      arbFr: Arbitrary[F[R]],
      arbFo: Arbitrary[F[O]],
      arbO: Arbitrary[O],
      arbFu: Arbitrary[F[Unit]]
  ): Arbitrary[Pull[F, O, R]] =
    Arbitrary(
      Gen.oneOf(
        arbitrary[R].map(Pull.pure(_)),
        arbitrary[F[R]].map(Pull.eval(_)),
        arbitrary[Stream[F, O]].flatMap(s => arbitrary[R].map(r => s.pull.echo >> Pull.pure(r)))
      )
    )

}
