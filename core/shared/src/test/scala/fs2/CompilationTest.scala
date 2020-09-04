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

package notfs2

import fs2._

import cats.{Applicative, Id}
import cats.effect.{ContextShift, IO, Resource, Timer}

object ThisModuleShouldCompile {
  implicit val timerIO: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.Implicits.global)
  implicit val contextShiftIO: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  /* Some checks that `.pull` can be used without annotations */
  Stream(1, 2, 3, 4).through(_.take(2))
  Stream.eval(IO.pure(1)).through(_.take(2))
  Stream(1, 2, 3).covary[IO].pull.uncons1.void.stream
  Stream.eval(IO.pure(1)).pull.uncons1.void.stream

  /* Also in a polymorphic context. */
  def a[F[_], A](s: Stream[F, A]) = s.through(_.take(2))
  def b[F[_], A](s: Stream[F, A]): Stream[F, A] = s.through(_.take(2))
  def c[F[_], A](s: Stream[F, A]): Stream[F, A] = s.through(_.take(2))

  Stream.empty[IO]
  Stream.empty.covary[IO]
  Stream.empty.covaryAll[IO, Int]
  Stream.empty.covaryOutput[Int]

  Stream(1, 2, 3) ++ Stream(4, 5, 6)
  Stream(1, 2, 3) ++ Stream.eval(IO.pure(4))
  Stream(1, 2, 3) ++ Stream.eval(IO.pure(4))
  Stream.eval(IO.pure(4)) ++ Stream(1, 2, 3)
  Stream.eval(IO.pure(4)) ++ Stream(1, 2, 3).covary[IO]
  Stream.eval(IO.pure(4)) ++ (Stream(1, 2, 3): Stream[IO, Int])
  Stream(1, 2, 3).flatMap(i => Stream.eval(IO.pure(i)))
  (Stream(1, 2, 3)
    .covary[IO])
    .pull
    .uncons1
    .flatMap {
      case Some((hd, _)) => Pull.output1(hd)
      case None          => Pull.done
    }
    .stream
  Stream(1, 2, 3).pull.uncons1.flatMap {
    case Some((hd, _)) => Pull.output1(hd)
    case None          => Pull.done
  }.stream
  Stream(1, 2, 3).pull.uncons1.flatMap {
    case Some((hd, _)) => Pull.eval(IO.pure(1)) >> Pull.output1(hd)
    case None          => Pull.done
  }.stream
  (Stream(1, 2, 3).evalMap(IO(_))): Stream[IO, Int]
  (Stream(1, 2, 3).flatMap(i => Stream.eval(IO(i)))): Stream[IO, Int]

  val s: Stream[IO, Int] = if (true) Stream(1, 2, 3) else Stream.eval(IO(10))

  val t2p: Pipe[Pure, Int, Int] = _.take(2)
  val t2: Pipe[IO, Int, Int] = _.take(2)
  val p2: Pipe2[IO, Int, Int, Int] = (s1, s2) => s1.interleave(s2)
  t2.attachL(p2)
  t2.attachR(p2)

  val p: Pull[Pure, Nothing, Option[(Chunk[Int], Stream[Pure, Int])]] = Stream(1, 2, 3).pull.uncons
  val q: Pull[IO, Nothing, Option[(Chunk[Int], Stream[Pure, Int])]] = p

  val streamId: Stream[Id, Int] = Stream(1, 2, 3)
  (streamId.covaryId[IO]): Stream[IO, Int]

  def polyId[F[_]: Applicative, A](stream: Stream[Id, A]): Stream[F, A] =
    stream.covaryId[F].through(_.take(2))

  // Ensure that Stream#flatMap is favored over cats's flatMap
  {
    import cats.syntax.all._
    1 |+| 1 // Mask unused warning from cats.syntax.all._ import
    Stream(1, 2, 3).flatMap(i => Stream.eval(IO.pure(i)))
    (Stream(1, 2, 3).flatMap(i => Stream.eval(IO(i)))): Stream[IO, Int]
  }

  // Join a pure stream of effectful streams without type annotations
  Stream(s, s).parJoinUnbounded

  // Join an effectul stream of pure streams requires type annotation on inner stream
  Stream[IO, Stream[IO, Nothing]](Stream.empty).parJoinUnbounded

  val pure: List[Int] = Stream.range(0, 5).compile.toList
  val io: IO[List[Int]] = Stream.range(0, 5).covary[IO].compile.toList
  val resource: Resource[IO, List[Int]] = Stream.range(0, 5).covary[IO].compile.resource.toList

  // Ensure that .to(Collector) syntax works even when target type is known (regression #1709)
  val z: List[Int] = Stream.range(0, 5).compile.to(List)
  Stream.empty[Id].covaryOutput[Byte].compile.to(Chunk): Chunk[Byte]

  Stream(1, 2, 3).compile.to(Set)
}
