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

import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.effect.{IO, SyncIO}
import cats.syntax.all._

import scala.annotation.nowarn
import scala.concurrent.duration._
import org.scalacheck.Prop.forAll
import org.scalacheck.effect.PropF.forAllF

@nowarn("cat=w-flag-dead-code")
class StreamZipSuite extends Fs2Suite {

  group("zip") {
    test("propagate error from closing the root scope") {
      val s1 = Stream.bracket(SyncIO(1))(_ => SyncIO.unit)
      val s2 = Stream.bracket(SyncIO("a"))(_ => SyncIO.raiseError(new Err))

      val r1 = s1.zip(s2).compile.drain.attempt.unsafeRunSync()
      assert(r1.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
      val r2 = s2.zip(s1).compile.drain.attempt.unsafeRunSync()
      assert(r2.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
    }

    test("issue #941 - scope closure issue") {
      assertEquals(
        Stream(1, 2, 3)
          .map(_ + 1)
          .repeat
          .zip(Stream(4, 5, 6).map(_ + 1).repeat)
          .take(4)
          .toList,
        List((2, 5), (3, 6), (4, 7), (2, 5))
      )
    }

    test("zipWith left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assertEquals(ones.zipWith(s)(_ + _).toList, List("1A", "1B", "1C"))
      assertEquals(s.zipWith(ones)(_ + _).toList, List("A1", "B1", "C1"))
    }

    test("zipWith both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assertEquals(ones.zipWith(as)(_ + _).take(3).toList, List("1A", "1A", "1A"))
      assertEquals(as.zipWith(ones)(_ + _).take(3).toList, List("A1", "A1", "A1"))
    }

    test("zipAllWith left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assertEquals(
        ones.zipAllWith(s)("2", "Z")(_ + _).take(5).toList,
        List("1A", "1B", "1C", "1Z", "1Z")
      )
      assertEquals(
        s.zipAllWith(ones)("Z", "2")(_ + _).take(5).toList,
        List("A1", "B1", "C1", "Z1", "Z1")
      )
    }

    test("zipAllWith both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assertEquals(
        ones.zipAllWith(as)("2", "Z")(_ + _).take(3).toList,
        List("1A", "1A", "1A")
      )
      assertEquals(
        as.zipAllWith(ones)("Z", "2")(_ + _).take(3).toList,
        List("A1", "A1", "A1")
      )
    }

    test("zip left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assertEquals(ones.zip(s).toList, List("1" -> "A", "1" -> "B", "1" -> "C"))
      assertEquals(s.zip(ones).toList, List("A" -> "1", "B" -> "1", "C" -> "1"))
    }

    test("zip both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assertEquals(ones.zip(as).take(3).toList, List("1" -> "A", "1" -> "A", "1" -> "A"))
      assertEquals(as.zip(ones).take(3).toList, List("A" -> "1", "A" -> "1", "A" -> "1"))
    }

    test("zipAll left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assertEquals(
        ones.zipAll(s)("2", "Z").take(5).toList,
        List(
          "1" -> "A",
          "1" -> "B",
          "1" -> "C",
          "1" -> "Z",
          "1" -> "Z"
        )
      )
      assertEquals(
        s.zipAll(ones)("Z", "2").take(5).toList,
        List(
          "A" -> "1",
          "B" -> "1",
          "C" -> "1",
          "Z" -> "1",
          "Z" -> "1"
        )
      )
    }

    test("zipAll both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assertEquals(
        ones.zipAll(as)("2", "Z").take(3).toList,
        List("1" -> "A", "1" -> "A", "1" -> "A")
      )
      assertEquals(
        as.zipAll(ones)("Z", "2").take(3).toList,
        List("A" -> "1", "A" -> "1", "A" -> "1")
      )
    }

    group("zip with scopes") {
      test("1") {
        // this tests that streams opening resources on each branch will close
        // scopes independently.
        val s = Stream(0).scope
        assertEquals((s ++ s).zip(s).toList, List((0, 0)))
      }
      def brokenZip[F[_], A, B](s1: Stream[F, A], s2: Stream[F, B]): Stream[F, (A, B)] = {
        def go(s1: Stream[F, A], s2: Stream[F, B]): Pull[F, (A, B), Unit] =
          s1.pull.uncons1.flatMap {
            case Some((hd1, tl1)) =>
              s2.pull.uncons1.flatMap {
                case Some((hd2, tl2)) =>
                  Pull.output1((hd1, hd2)) >> go(tl1, tl2)
                case None => Pull.done
              }
            case None => Pull.done
          }
        go(s1, s2).stream
      }
      test("2") {
        val s = Stream(0).scope
        intercept[Throwable](brokenZip(s ++ s, s.zip(s)).compile.toList)
      }
      test("3") {
        Logger[IO]
          .flatMap { logger =>
            def s(tag: String) =
              logger.logLifecycle(tag) >> {
                logger.logLifecycle(s"$tag - 1") ++ logger.logLifecycle(s"$tag - 2")
              }

            s("a").zip(s("b")).compile.drain >>
              logger.get.assertEquals(
                List(
                  LogEvent.Acquired("a"),
                  LogEvent.Acquired("a - 1"),
                  LogEvent.Acquired("b"),
                  LogEvent.Acquired("b - 1"),
                  LogEvent.Released("a - 1"),
                  LogEvent.Acquired("a - 2"),
                  LogEvent.Released("b - 1"),
                  LogEvent.Acquired("b - 2"),
                  LogEvent.Released("a - 2"),
                  LogEvent.Released("a"),
                  LogEvent.Released("b - 2"),
                  LogEvent.Released("b")
                )
              )
          }
      }
    }

    test("issue #1120 - zip with uncons") {
      // this tests we can properly look up scopes for the zipped streams
      val rangeStream = Stream.emits((0 to 3).toList)
      assertEquals(
        rangeStream.zip(rangeStream).attempt.map(identity).toVector,
        Vector(
          Right((0, 0)),
          Right((1, 1)),
          Right((2, 2)),
          Right((3, 3))
        )
      )
    }
  }

  group("parZip") {
    test("parZip outputs the same results as zip") {
      forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        val par = s1.covary[IO].parZip(s2)
        val seq = s1.zip(s2).toList
        par.compile.toList.assertEquals(seq)
      }
    }

    test("parZip evaluates effects with bounded concurrency") {
      // track progress of the computation
      @volatile var lhs: Int = 0
      @volatile var rhs: Int = 0
      @volatile var output: Vector[(String, Int)] = Vector()

      // synchronises lhs and rhs to test both sides of the race in parZip
      def parZipRace[A, B](lhs: Stream[IO, A], rhs: Stream[IO, B]) = {
        val rate = Stream(1, 2).repeat
        val skewedRate = Stream(2, 1).repeat
        def sync[C]: Pipe2[IO, C, Int, C] =
          (in, rate) => rate.evalMap(n => IO.sleep(n.seconds)).zipRight(in)

        lhs.through2(rate)(sync).parZip(rhs.through2(skewedRate)(sync))
      }

      val stream = parZipRace(
        Stream("a", "b", "c").evalTap(_ => IO { lhs = lhs + 1 }),
        Stream(1, 2, 3).evalTap(_ => IO { rhs = rhs + 1 })
      ).evalTap(x => IO { output = output :+ x })

      for {
        testControlResult <- TestControl.execute(stream.compile.toVector)

        // lhsAt, rhsAt and output at time T = [1s, 2s, ..]
        snapshots = Vector(
          (1, 0, Vector()),
          (1, 1, Vector("a" -> 1)),
          (1, 2, Vector("a" -> 1)),
          (2, 2, Vector("a" -> 1, "b" -> 2)),
          (3, 2, Vector("a" -> 1, "b" -> 2)),
          (3, 3, Vector("a" -> 1, "b" -> 2, "c" -> 3))
        )

        _ <- testControlResult.tick

        _ <- snapshots.traverse_ { snapshot =>
          testControlResult.nextInterval.flatMap(nextInterval =>
            testControlResult
              .advanceAndTick(nextInterval)
              .map(_ => assertEquals((lhs, rhs, output), snapshot))
          )
        }

        _ <- testControlResult.tickAll

        _ <- testControlResult.results
          .flatMap {
            case None => IO.raiseError[Vector[(String, Int)]](new Throwable("Results not found"))
            case Some(Outcome.Succeeded(v)) => IO.pure(v)
            case Some(Outcome.Errored(ex))  => IO.raiseError[Vector[(String, Int)]](ex)
            case Some(Outcome.Canceled()) =>
              IO.raiseError[Vector[(String, Int)]](
                new Throwable("Cancelled found on results outcome")
              )
          }
          .map(r => assertEquals(r, snapshots.last._3))
      } yield ()
    }
  }

  property("zipWithIndex") {
    forAll { (s: Stream[Pure, Int]) =>
      val expected = s.toList.zipWithIndex.map { case (e, i) => e -> i.toLong }
      assertEquals(s.zipWithIndex.toList, expected)
    }
  }

  group("zipWithNext") {
    property("1") {
      forAll { (s: Stream[Pure, Int]) =>
        val xs = s.toList

        assertEquals(
          s.zipWithNext.toList,
          xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
        )
      }
    }

    test("2") {
      assertEquals(Stream().zipWithNext.toList, Nil)
      assertEquals(Stream(0).zipWithNext.toList, List((0, None)))
      assertEquals(Stream(0, 1, 2).zipWithNext.toList, List((0, Some(1)), (1, Some(2)), (2, None)))
    }
  }

  group("zipWithPrevious") {
    property("1") {
      forAll { (s: Stream[Pure, Int]) =>
        val xs = s.toList

        assertEquals(
          s.zipWithPrevious.toList,
          (None +: xs.map(Some(_))).zip(xs)
        )
      }
    }

    test("2") {
      assertEquals(Stream().zipWithPrevious.toList, Nil)
      assertEquals(Stream(0).zipWithPrevious.toList, List((None, 0)))
      assertEquals(
        Stream(0, 1, 2).zipWithPrevious.toList,
        List((None, 0), (Some(0), 1), (Some(1), 2))
      )
    }
  }

  group("zipWithPreviousAndNext") {
    property("1") {
      forAll { (s: Stream[Pure, Int]) =>
        val xs = s.toList
        val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
        val zipWithPreviousAndNext = zipWithPrevious
          .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
          .map { case ((prev, that), next) => (prev, that, next) }

        assertEquals(
          s.zipWithPreviousAndNext.toList,
          zipWithPreviousAndNext
        )
      }
    }

    test("2") {
      assertEquals(Stream().zipWithPreviousAndNext.toList, Nil)
      assertEquals(Stream(0).zipWithPreviousAndNext.toList, List((None, 0, None)))
      assertEquals(
        Stream(0, 1, 2).zipWithPreviousAndNext.toList,
        List(
          (None, 0, Some(1)),
          (Some(0), 1, Some(2)),
          (Some(1), 2, None)
        )
      )
    }
  }

  test("zipWithScan") {
    assertEquals(
      Stream("uno", "dos", "tres", "cuatro")
        .zipWithScan(0)(_ + _.length)
        .toList,
      List("uno" -> 0, "dos" -> 3, "tres" -> 6, "cuatro" -> 10)
    )
    assertEquals(Stream().zipWithScan(())((_, _) => ???).toList, Nil)
  }

  test("zipWithScan1") {
    assertEquals(
      Stream("uno", "dos", "tres", "cuatro")
        .zipWithScan1(0)(_ + _.length)
        .toList,
      List("uno" -> 3, "dos" -> 6, "tres" -> 10, "cuatro" -> 16)
    )
    assertEquals(Stream().zipWithScan1(())((_, _) => ???).toList, Nil)
  }

  group("regressions") {
    test("#1089") {
      (Stream.chunk(Chunk.array(Array.fill(2000)(1.toByte))) ++ Stream.eval(
        IO.never[Byte]
      )).take(2000).chunks.compile.toVector
    }

    test("#1107 - scope") {
      Stream(0)
        .covary[IO]
        .scope
        .repeat
        .take(10000)
        .flatMap(_ => Stream.empty) // Never emit an element downstream
        .mapChunks(identity) // Use a combinator that calls Stream#pull.uncons
        .compile
        .drain
    }

    test("#1107 - queue") {
      Stream
        .range(0, 10000)
        .covary[IO]
        .chunkLimit(1)
        .unchunks
        .prefetch
        .flatMap(_ => Stream.empty)
        .mapChunks(identity)
        .compile
        .drain
    }
  }
}
