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

import cats.effect.SyncIO
import cats.syntax.all._

class StreamPerformanceSuite extends Fs2Suite {
  val Ns = {
    val all = List(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
    if (isJVM) all else all.take(8)
  }

  group("left-associated ++") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N).map(Stream.emit).foldLeft(Stream.emit(0))(_ ++ _).toVector,
          Vector
            .range(0, N)
        )
      }
    }
  }

  group("right-associated ++") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (0 until N)
            .map(Stream.emit)
            .foldRight(Stream.empty: Stream[Pure, Int])(_ ++ _)
            .toVector,
          Vector.range(0, N)
        )
      }
    }
  }

  group("left-associated flatMap 1") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0))((acc, a) => acc.flatMap(_ => a))
            .toVector,
          Vector(N - 1)
        )
      }
    }
  }

  group("left-associated map 1") {
    Ns.foreach { N =>
      test(N.toString.ignore) { // Fails
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0))((acc, _) => acc.map(_ + 1))
            .toVector,
          Vector(N - 1)
        )
      }
    }
  }

  group("left-associated eval() ++ flatMap 1") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0).covary[SyncIO])((acc, a) =>
              acc.flatMap(_ => Stream.eval(SyncIO.unit).flatMap(_ => a))
            )
            .compile
            .toVector
            .unsafeRunSync(),
          Vector(N - 1)
        )
      }
    }
  }

  group("right-associated flatMap 1") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0))((acc, a) => a.flatMap(_ => acc))
            .toVector,
          Vector(0)
        )
      }
    }
  }

  group("right-associated eval() ++ flatMap 1") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0).covary[SyncIO])((acc, a) =>
              a.flatMap(_ => Stream.eval(SyncIO.unit).flatMap(_ => acc))
            )
            .compile
            .toVector
            .unsafeRunSync(),
          Vector(0)
        )
      }
    }
  }

  group("left-associated flatMap 2") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .foldLeft(Stream.emit(0) ++ Stream.emit(1) ++ Stream.emit(2))((acc, a) =>
              acc.flatMap(_ => a)
            )
            .toVector,
          Vector(N - 1, N - 1, N - 1)
        )
      }
    }
  }

  group("right-associated flatMap 2") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          (1 until N)
            .map(Stream.emit)
            .reverse
            .foldLeft(Stream.emit(0) ++ Stream.emit(1) ++ Stream.emit(2))((acc, a) =>
              a.flatMap(_ => acc)
            )
            .toVector,
          Vector(0, 1, 2)
        )
      }
    }
  }

  group("transduce (id)") {
    Ns.foreach { N =>
      test(N.toString) {
        val s: Stream[Pure, Int] = Stream
          .chunk(Chunk.from(0 until N))
          .repeatPull {
            _.uncons1.flatMap {
              case None           => Pull.pure(None)
              case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
            }
          }
        assertEquals(s.toVector, Vector.range(0, N))
      }
    }
  }

  group("bracket + handleErrorWith") {
    group("left associated") {
      Ns.foreach { N =>
        test(N.toString) {
          Counter[SyncIO]
            .flatMap { open =>
              Counter[SyncIO].flatMap { ok =>
                val bracketed: Stream[SyncIO, Int] = Stream
                  .bracket(open.increment)(_ => ok.increment >> open.decrement)
                  .flatMap(_ => Stream(1) ++ Stream.raiseError[SyncIO](new Err))
                val s: Stream[SyncIO, Int] =
                  List
                    .fill(N)(bracketed)
                    .foldLeft(Stream.raiseError[SyncIO](new Err): Stream[SyncIO, Int]) { (acc, hd) =>
                      acc.handleErrorWith(_ => hd)
                    }
                s.compile.toList.attempt
                  .flatMap(_ => (ok.get, open.get).tupled)
                  .map { case (ok, open) =>
                    assertEquals(ok, N.toLong)
                    assertEquals(open, 0L)
                  }
              }
            }
            .unsafeRunSync()
        }
      }
    }

    group("right associated") {
      Ns.foreach { N =>
        test(N.toString) {
          Counter[SyncIO]
            .flatMap { open =>
              Counter[SyncIO].flatMap { ok =>
                val bracketed: Stream[SyncIO, Int] = Stream
                  .bracket(open.increment)(_ => ok.increment >> open.decrement)
                  .flatMap(_ => Stream(1) ++ Stream.raiseError[SyncIO](new Err))
                val s: Stream[SyncIO, Int] = List
                  .fill(N)(bracketed)
                  .foldLeft(Stream.raiseError[SyncIO](new Err): Stream[SyncIO, Int]) { (tl, hd) =>
                    hd.handleErrorWith(_ => tl)
                  }
                s.compile.toList.attempt
                  .flatMap(_ => (ok.get, open.get).tupled)
                  .map { case (ok, open) =>
                    assertEquals(ok, N.toLong)
                    assertEquals(open, 0L)
                  }
              }
            }
            .unsafeRunSync()
        }
      }
    }
  }

  group("chunky flatMap") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          Stream.emits(Vector.range(0, N)).flatMap(i => Stream.emit(i)).toVector,
          Vector
            .range(0, N)
        )
      }
    }
  }

  group("chunky map with uncons") {
    Ns.foreach { N =>
      test(N.toString) {
        assertEquals(
          Stream
            .emits(Vector.range(0, N))
            .map(i => i)
            .chunks
            .unchunks
            .toVector,
          Vector.range(0, N)
        )
      }
    }
  }
}
