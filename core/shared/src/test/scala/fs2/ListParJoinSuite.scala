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

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

class ListParJoinSuite extends Fs2Suite {
  override def munitIOTimeout = 1.minute

  test("no concurrency".ignore) {
    forAllF { (s: Stream[Pure, Int]) =>
      s.covary[IO].map(Stream.emit(_)).parJoin(1).assertEmits(s.toList)
    }
  }

  test("concurrency n".ignore) {
    forAllF { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      s.covary[IO].map(Stream.emit(_)).parJoin(n).assertEmitsUnorderedSameAs(s)
    }
  }

  test("concurrency unbounded") {
    forAllF { (s: List[Int]) =>
      s.map(Stream.emit(_).covary[IO]).parJoinUnbounded.assertEmitsUnordered(s)
    }
  }

  test("concurrent flattening n".ignore) {
    forAllF { (s: Stream[Pure, Stream[Pure, Int]], n0: Int) =>
      val n = (n0 % 20).abs + 1
      s.covary[IO].parJoin(n).assertEmitsUnorderedSameAs(s.flatten)
    }
  }

  test("concurrent flattening unbounded") {
    forAllF { (s: List[Stream[Pure, Int]]) =>
      s.map(_.covary[IO]).parJoinUnbounded.assertEmitsUnorderedSameAs(Stream(s: _*).flatten)
    }
  }

  test("merge consistency n".ignore) {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val parJoined = List(s1.covary[IO], s2).parJoinUnbounded
      val merged = s1.covary[IO].merge(s2)
      parJoined.assertEmitsUnorderedSameAs(merged)
    }
  }

  test("merge consistency unbounded") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val parJoined = List(s1.covary[IO], s2).parJoinUnbounded
      val merged = s1.covary[IO].merge(s2)
      parJoined.assertEmitsUnorderedSameAs(merged)
    }
  }

  group("hangs unbounded") {
    val full = Stream.constant(42).chunks.evalTap(_ => IO.cede).unchunks
    val hang = Stream.repeatEval(IO.never[Unit])
    val hang2: Stream[IO, Nothing] = full.drain
    val hang3: Stream[IO, Nothing] =
      Stream
        .repeatEval[IO, Unit](IO.async_[Unit](cb => cb(Right(()))) >> IO.cede)
        .drain

    test("1 unbounded") {
      List(full, hang).parJoinUnbounded.take(1).assertEmits(List(42))
    }
    test("2 unbounded") {
      List(full, hang2).parJoinUnbounded.take(1).assertEmits(List(42))
    }
    test("3 unbounded") {
      List(full, hang3).parJoinUnbounded.take(1).assertEmits(List(42))
    }
    test("4 unbounded") {
      List(hang3, hang2, full).parJoinUnbounded.take(1).assertEmits(List(42))
    }
  }

  test("outer failed unbounded") {
    List(
      Stream.sleep_[IO](1.minute),
      Stream.raiseError[IO](new Err)
    ).parJoinUnbounded.compile.drain
      .intercept[Err]
  }

  test("propagate error from inner stream before ++ unbounded") {
    val err = new Err

    (List(Stream.raiseError[IO](err)).parJoinUnbounded ++ Stream.emit(1)).compile.drain
      .intercept[Err]
  }

  group("short-circuiting transformers") {
    test("do not block while evaluating a list of streams in IO in parallel") {
      def f(n: Int): Stream[IO, String] = Stream(n).map(_.toString)
      val expected = Set("1", "2", "3")
      List(1, 2, 3).map(f).parJoinUnbounded.assertEmitsUnordered(expected)
    }

    test(
      "do not block while evaluating a list of streams in EitherT[IO, Throwable, *] in parallel - right"
    ) {
      def f(n: Int): Stream[EitherT[IO, Throwable, *], String] = Stream(n).map(_.toString)

      val expected = Set("1", "2", "3")

      List(1, 2, 3)
        .map(f)
        .parJoinUnbounded
        .compile
        .toList
        .map(_.toSet)
        .value
        .flatMap(actual => IO(assertEquals(actual, Right(expected))))
    }

    test(
      "do not block while evaluating a list of streams in EitherT[IO, Throwable, *] in parallel - left"
    ) {
      case object TestException extends Throwable with NoStackTrace

      def f(n: Int): Stream[EitherT[IO, Throwable, *], String] =
        if (n % 2 != 0) Stream(n).map(_.toString)
        else Stream.eval[EitherT[IO, Throwable, *], String](EitherT.leftT(TestException))

      List(1, 2, 3)
        .map(f)
        .parJoinUnbounded
        .compile
        .toList
        .value
        .flatMap { actual =>
          IO(assertEquals(actual, Left(TestException)))
        }
    }

    test(
      "do not block while evaluating a stream in EitherT[IO, Throwable, *] with multiple parJoins".ignore
    ) {
      case object TestException extends Throwable with NoStackTrace
      type F[A] = EitherT[IO, Throwable, A]

      Stream
        .range(1, 64)
        .covary[F]
        .map(i =>
          Stream.eval[F, Unit](
            if (i == 7) EitherT.leftT(TestException)
            else EitherT.right(IO.unit)
          )
        )
        .parJoin(4)
        .map(_ => Stream.eval[F, Unit](EitherT.right(IO.unit)))
        .parJoin(4)
        .compile
        .drain
        .value
        .assertEquals(Left(TestException))
    }

    test("do not block while evaluating an EitherT.left outer stream") {
      case object TestException extends Throwable with NoStackTrace

      def f(n: Int): Stream[EitherT[IO, Throwable, *], String] = Stream(n).map(_.toString)

      List(
        Stream
          .eval[EitherT[IO, Throwable, *], Int](EitherT.leftT[IO, Int](TestException))
          .map(f)
      ).parJoinUnbounded.compile.toList.value
        .flatMap { actual =>
          IO(assertEquals(actual, Left(TestException)))
        }
    }

    test(
      "do not block while evaluating a list of streams in OptionT[IO, *] in parallel - some"
    ) {
      def f(n: Int): Stream[OptionT[IO, *], String] = Stream(n).map(_.toString)

      List(1, 2, 3)
        .map(f)
        .parJoinUnbounded
        .compile
        .toList
        .map(_.toSet)
        .value
        .flatMap { actual =>
          IO(assertEquals(actual, Some(Set("1", "2", "3"))))
        }
    }

    test(
      "do not block while evaluating a stream of streams in OptionT[IO, *] in parallel - none"
    ) {
      def f(n: Int): Stream[OptionT[IO, *], String] =
        if (n % 2 != 0) Stream(n).map(_.toString)
        else Stream.eval[OptionT[IO, *], String](OptionT.none)

      List(1, 2, 3)
        .map(f)
        .parJoinUnbounded
        .compile
        .toList
        .value
        .flatMap { actual =>
          IO(assertEquals(actual, None))
        }
    }

    test("do not block while evaluating an OptionT.none outer stream") {
      def f(n: Int): Stream[OptionT[IO, *], String] = Stream(n).map(_.toString)

      List(
        Stream
          .eval[OptionT[IO, *], Int](OptionT.none[IO, Int])
          .map(f)
      ).parJoinUnbounded.compile.toList.value
        .flatMap(actual => IO(assertEquals(actual, None)))
    }

    test("pull at most 2 elements from inner streams") {
      IO.ref(0).flatMap { ref =>
        List(
          Stream.repeatEval(ref.getAndUpdate(_ + 1).void),
          Stream.empty
        ).parJoinUnbounded.take(1).compile.drain >> ref.get.map(x => assert(x <= 2))
      }
    }
  }
}
