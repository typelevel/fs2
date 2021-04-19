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

import cats.effect.IO
import cats.effect.kernel.Ref
import org.scalacheck.Prop.forAll

class PullSuite extends Fs2Suite {
  test("early termination of uncons") {
    Ref.of[IO, Int](0).flatMap { ref =>
      Stream(1, 2, 3)
        .onFinalize(ref.update(_ + 1))
        .pull
        .echoChunk
        .void
        .stream
        .compile
        .drain >> ref.get.assertEquals(1)
    }
  }

  property("fromEither") {
    forAll { (either: Either[Throwable, Int]) =>
      val pull: Pull[Fallible, Int, Unit] = Pull.fromEither[Fallible](either)

      either match {
        case Left(l) => assertEquals(pull.stream.compile.toList, Left(l))
        case Right(r) =>
          assertEquals(pull.stream.compile.toList, Right(List(r)))
      }
    }
  }

  property("loop") {
    forAll { (list: List[Int]) =>
      def customOutput(s: List[Int]): Pull[Fallible, Int, Option[List[Int]]] =
        s match {
          case head :: tail => Pull.output1(head).as(Some(tail))
          case Nil          => Pull.pure(None)
        }

      val result = Pull.loop(customOutput)(list).stream.compile.toList

      assertEquals(result, Right(list))
    }
  }

  property("loopEither") {
    forAll { (list: List[Int]) =>
      def customOutputReturnsString(s: List[Int]): Pull[Fallible, Int, Either[List[Int], String]] =
        s match {
          case head :: tail => Pull.output1(head).as(Left(tail))
          case Nil          => Pull.pure(Right("done"))
        }

      val result =
        Pull
          .loopEither(customOutputReturnsString)(list)
          .map(v => assert(v == "done"))
          .void
          .stream
          .compile
          .toList

      assertEquals(result, Right(list))
    }
  }
}
